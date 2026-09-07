package com.iot.control

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云 IoT MQTT 连接管理器
 *
 * 连接参数说明:
 * - Broker: tcp://{productKey}.iot-as-mqtt.{regionId}.aliyuncs.com:1883
 * - ClientId: {clientId}|securemode=2,signmethod=hmacsha1,timestamp={ts}|
 * - Username: {deviceName}&{productKey}
 * - Password: HMAC-SHA1(content, deviceSecret) 的十六进制字符串
 *   content = "clientId{clientId}deviceName{deviceName}productKey{productKey}timestamp{ts}"
 */
class AliyunIotManager(private val context: Context) {

    companion object {
        private const val TAG = "AliyunIot"
        private const val DEFAULT_REGION = "cn-shanghai"
        private const val POWER_PROPERTY = "PowerSwitch"
        private const val LIGHT_PROPERTY = "LightSwitch"
        private const val TEMP_PROPERTY = "Temperature"
    }

    @Volatile
    private var mqttClient: MqttAsyncClient? = null

    data class DeviceConfig(
        val productKey: String,
        val deviceName: String,
        val deviceSecret: String,
        val region: String = DEFAULT_REGION
    )

    enum class Status {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    var statusListener: ((Status, String) -> Unit)? = null
    var messageListener: ((String, String) -> Unit)? = null

    /**
     * 连接阿里云 IoT（在后台线程执行）
     */
    fun connect(config: DeviceConfig) {
        if (config.productKey.isBlank() || config.deviceName.isBlank() || config.deviceSecret.isBlank()) {
            statusListener?.invoke(Status.ERROR, "设备三元组不能为空")
            return
        }

        statusListener?.invoke(Status.CONNECTING, "正在连接阿里云 IoT…")

        // 在后台线程执行连接，避免 NetworkOnMainThreadException
        Thread {
            try {
                connectInternal(config)
            } catch (e: Exception) {
                Log.e(TAG, "连接异常", e)
                statusListener?.invoke(Status.ERROR, "连接异常: ${e.message ?: "未知错误"}")
            }
        }.start()
    }

    private fun connectInternal(config: DeviceConfig) {
        val region = config.region.ifBlank { DEFAULT_REGION }
        val broker = "tcp://${config.productKey}.iot-as-mqtt.$region.aliyuncs.com:1883"
        val clientId = "${config.productKey}.${config.deviceName}.android"

        val timestamp = System.currentTimeMillis().toString()
        val content = "clientId$clientId" +
                "deviceName${config.deviceName}" +
                "productKey${config.productKey}" +
                "timestamp$timestamp"

        val mqttPassword = hmacSha1Hex(content, config.deviceSecret)
        val username = "${config.deviceName}&${config.productKey}"
        val mqttClientId = "$clientId|securemode=2,signmethod=hmacsha1,timestamp=$timestamp|"

        // 先断开旧连接
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) {
            Log.w(TAG, "断开旧连接失败: ${e.message}")
        }

        val client = MqttAsyncClient(broker, mqttClientId, MemoryPersistence())

        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.i(TAG, "MQTT 连接成功 (reconnect=$reconnect)")
                statusListener?.invoke(Status.CONNECTED, "已连接到阿里云 IoT")
                // 启动前台服务保活
                IoTConnectionService.start(context)
                // 订阅平台下发的属性设置指令（下行主题）
                val pk = config.productKey
                val dn = config.deviceName
                val topics = listOf(
                    "/sys/$pk/$dn/thing/service/property/set",         // 平台下发属性设置
                    "/sys/$pk/$dn/thing/service/property/get",          // 平台查询属性
                    "/sys/$pk/$dn/thing/event/property/post_reply"      // 上报后平台的回复
                )
                topics.forEach { topic ->
                    try {
                        client.subscribe(topic, 0)
                        Log.i(TAG, "已订阅: $topic")
                    } catch (e: Exception) {
                        Log.e(TAG, "订阅失败 $topic: ${e.message}")
                    }
                }
            }

            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "MQTT 连接丢失: ${cause?.message}")
                statusListener?.invoke(Status.DISCONNECTED, "连接已断开: ${cause?.message ?: ""}")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val payload = message?.toString() ?: ""
                Log.d(TAG, "收到消息 topic=$topic payload=$payload")
                // 收到平台下发的 property/set 指令时，自动回复 set_reply
                if (topic?.endsWith("thing/service/property/set") == true) {
                    replyToPropertySet(config, payload)
                }
                messageListener?.invoke(topic ?: "", payload)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            keepAliveInterval = 60
            connectionTimeout = 10
            userName = username
            password = mqttPassword.toCharArray()
        }

        mqttClient = client

        client.connect(options, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) {
                Log.i(TAG, "连接回调成功")
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                val msg = "连接失败: ${exception?.message ?: "未知错误"}"
                Log.e(TAG, msg, exception)
                statusListener?.invoke(Status.ERROR, msg)
            }
        })
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        IoTConnectionService.stop(context)
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
            mqttClient = null
            statusListener?.invoke(Status.DISCONNECTED, "已断开连接")
        } catch (e: Exception) {
            Log.e(TAG, "断开失败: ${e.message}")
            mqttClient = null
        }
    }

    /**
     * 设置设备开关属性
     * @param on true=开启(1), false=关闭(0)
     */
    fun setPower(config: DeviceConfig, on: Boolean) {
        val value = if (on) 1 else 0
        publishProperty(config, POWER_PROPERTY, value, "电源开关")
    }

    /**
     * 设置灯开关属性
     * @param on true=开启(1), false=关闭(0)
     */
    fun setLight(config: DeviceConfig, on: Boolean) {
        val value = if (on) 1 else 0
        publishProperty(config, LIGHT_PROPERTY, value, "灯开关")
    }

    /**
     * 设置温度属性
     * @param value 温度值（支持浮点数）
     */
    fun setTemperature(config: DeviceConfig, value: Float) {
        publishProperty(config, TEMP_PROPERTY, value, "温度设置")
    }

    /**
     * 设置第 N 路开关 (1~10)
     * 属性名: Switch1 ~ Switch10
     * @param index 开关序号 1-10
     * @param on true=开启(1), false=关闭(0)
     */
    fun setSwitch(config: DeviceConfig, index: Int, on: Boolean) {
        if (index !in 1..10) return
        val value = if (on) 1 else 0
        publishProperty(config, "Switch$index", value, "开关$index")
    }

    /**
     * 一键控制所有 10 路开关
     * @param on true=全部开启(1), false=全部关闭(0)
     */
    fun setAllSwitches(config: DeviceConfig, on: Boolean) {
        val value = if (on) 1 else 0
        publishMultiProperties(config, (1..10).associate { "Switch$it" to value }, "一键${if (on) "开启" else "关闭"}全部开关")
    }

    /**
     * 通用属性上报方法（设备→平台）
     * 主题: /sys/{pk}/{dn}/thing/event/property/post
     */
    private fun publishProperty(config: DeviceConfig, propertyName: String, value: Number, label: String) {
        val client = mqttClient ?: run {
            statusListener?.invoke(Status.ERROR, "未连接，无法上报")
            return
        }
        if (!client.isConnected) {
            statusListener?.invoke(Status.ERROR, "未连接，无法上报")
            return
        }

        val topic = "/sys/${config.productKey}/${config.deviceName}/thing/event/property/post"
        val payload = """{"id":"${System.currentTimeMillis()}","version":"1.0","params":{"$propertyName":$value},"method":"thing.event.property.post"}"""

        Thread {
            try {
                client.publish(topic, payload.toByteArray(), 0, false)
                Log.i(TAG, "上报[$label]: $payload")
            } catch (e: Exception) {
                Log.e(TAG, "上报[$label]失败: ${e.message}")
                statusListener?.invoke(Status.ERROR, "$label 上报失败: ${e.message}")
            }
        }.start()
    }

    /**
     * 多属性一次性上报方法
     */
    private fun publishMultiProperties(config: DeviceConfig, params: Map<String, Number>, label: String) {
        val client = mqttClient ?: run {
            statusListener?.invoke(Status.ERROR, "未连接，无法上报")
            return
        }
        if (!client.isConnected) {
            statusListener?.invoke(Status.ERROR, "未连接，无法上报")
            return
        }

        val topic = "/sys/${config.productKey}/${config.deviceName}/thing/event/property/post"
        val paramsJson = params.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
        val payload = """{"id":"${System.currentTimeMillis()}","version":"1.0","params":{$paramsJson},"method":"thing.event.property.post"}"""

        Thread {
            try {
                client.publish(topic, payload.toByteArray(), 0, false)
                Log.i(TAG, "上报[$label]: $payload")
            } catch (e: Exception) {
                Log.e(TAG, "上报[$label]失败: ${e.message}")
                statusListener?.invoke(Status.ERROR, "$label 上报失败: ${e.message}")
            }
        }.start()
    }

    /**
     * 收到平台下发的 property/set 指令后，自动回复 set_reply
     * 主题: /sys/{pk}/{dn}/thing/service/property/set_reply
     * 载荷: {"id":"<原id>","code":200,"data":{}}
     */
    private fun replyToPropertySet(config: DeviceConfig, requestPayload: String) {
        val client = mqttClient ?: return
        if (!client.isConnected) return

        val reqId = try {
            val json = org.json.JSONObject(requestPayload)
            json.optString("id", "${System.currentTimeMillis()}")
        } catch (e: Exception) {
            "${System.currentTimeMillis()}"
        }

        val topic = "/sys/${config.productKey}/${config.deviceName}/thing/service/property/set_reply"
        val payload = """{"id":"$reqId","code":200,"data":{}}"""

        Thread {
            try {
                client.publish(topic, payload.toByteArray(), 0, false)
                Log.i(TAG, "回复 set_reply: $payload")
            } catch (e: Exception) {
                Log.e(TAG, "回复 set_reply 失败: ${e.message}")
            }
        }.start()
    }

    /**
     * HMAC-SHA1 签名并转十六进制
     */
    private fun hmacSha1Hex(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun isConnected(): Boolean = mqttClient?.isConnected == true
}
