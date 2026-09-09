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

    // 去重：记录上一条收到的消息，避免重复投递导致日志/状态重复显示
    @Volatile
    private var lastTopic: String? = null
    @Volatile
    private var lastPayload: String? = null

    /**
     * 标记用户是否主动断开过连接（持久化到 SharedPreferences，App 重启后仍有效）
     * - true: 主动断开过，应用启动时不自动重连，必须用户再次点击连接
     * - false: 未主动断开过（如意外掉线），保持自动重连
     */
    private val prefs by lazy {
        context.getSharedPreferences("iot_config", android.content.Context.MODE_PRIVATE)
    }

    fun isManualDisconnected(): Boolean = prefs.getBoolean("user_manual_disconnect", false)

    private fun setManualDisconnected(value: Boolean) {
        prefs.edit().putBoolean("user_manual_disconnect", value).apply()
    }

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

        // 用户主动点击连接 → 清除"主动断开"标记
        setManualDisconnected(false)

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
                // 订阅自定义 Topic（用于 MQTTX 等外部客户端下发指令）
                val customTopicUpdate = "/$pk/$dn/user/update"
                val customTopicGet = "/$pk/$dn/user/get"
                listOf(customTopicUpdate, customTopicGet).forEach { customTopic ->
                    try {
                        client.subscribe(customTopic, 0)
                        Log.i(TAG, "已订阅自定义Topic: $customTopic")
                    } catch (e: Exception) {
                        Log.e(TAG, "订阅自定义Topic失败 $customTopic: ${e.message}")
                    }
                }
            }

            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "MQTT 连接丢失: ${cause?.message}")
                statusListener?.invoke(Status.DISCONNECTED, "连接已断开: ${cause?.message ?: ""}")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val payload = message?.toString() ?: ""
                val topicStr = topic ?: ""
                Log.d(TAG, "收到消息 topic=$topic payload=$payload")
                // 去重：与上一条完全相同（同 topic + 同 payload 归一化后）则忽略
                // 归一化可避免 130 与 130.0 这类数值相同但字符串不同的重复
                val normalized = normalizeForDedup(payload)
                if (topicStr == lastTopic && normalized == lastPayload) {
                    Log.d(TAG, "重复消息已忽略: $payload")
                    return
                }
                lastTopic = topicStr
                lastPayload = normalized
                // 收到平台下发的 property/set 指令时，自动回复 set_reply
                if (topic?.endsWith("thing/service/property/set") == true) {
                    replyToPropertySet(config, payload)
                }
                messageListener?.invoke(topicStr, payload)
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
     * @param manual true=用户主动断开（启动后不自动重连）；false=程序内部断开（保持自动重连）
     */
    fun disconnect(manual: Boolean = true) {
        if (manual) {
            setManualDisconnected(true)
        }
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
     * 自定义下发 Topic: /{productKey}/{deviceName}/user/update
     */
    private fun customTopic(config: DeviceConfig): String =
        "/${config.productKey}/${config.deviceName}/user/update"

    /**
     * 构造自定义 JSON 并发布到 /user/update Topic
     * 统一纯数字格式: {"from":1,"type":<typeCode>,"index":<index>,"value":<value>}
     * type 编码: 1=switch 单路开关, 2=temperature 温度, 3=power 电源, 4=light 灯, 5=switch_all 全控
     * from=1 表示手机端
     */
    private fun publishCustomJson(config: DeviceConfig, typeCode: Int, value: Number, index: Int = 0, label: String) {
        val client = mqttClient ?: run {
            statusListener?.invoke(Status.ERROR, "未连接，无法下发")
            return
        }
        if (!client.isConnected) {
            statusListener?.invoke(Status.ERROR, "未连接，无法下发")
            return
        }

        val topic = customTopic(config)
        val payload = """{"from":1,"type":$typeCode,"index":$index,"value":$value}"""

        Thread {
            try {
                client.publish(topic, payload.toByteArray(Charsets.UTF_8), 0, false)
                Log.i(TAG, "下发[$label]: $topic $payload")
                statusListener?.invoke(Status.CONNECTED, "已下发 $label: $payload")
            } catch (e: Exception) {
                Log.e(TAG, "下发[$label]失败: ${e.message}")
                statusListener?.invoke(Status.ERROR, "$label 下发失败: ${e.message}")
            }
        }.start()
    }

    /**
     * 电源开关（自定义格式下发）type=3
     */
    fun setPower(config: DeviceConfig, on: Boolean) {
        val value = if (on) 1 else 0
        publishCustomJson(config, 3, value, label = "电源开关 ${if (on) "开" else "关"}")
    }

    /**
     * 灯开关（自定义格式下发）type=4
     */
    fun setLight(config: DeviceConfig, on: Boolean) {
        val value = if (on) 1 else 0
        publishCustomJson(config, 4, value, label = "灯开关 ${if (on) "开" else "关"}")
    }

    /**
     * 温度设置（自定义格式下发）type=2
     * @param value 温度值（支持浮点数）
     */
    fun setTemperature(config: DeviceConfig, value: Float) {
        // 整数温度以整数形式下发（25.0 → 25）
        val numValue: Number = if (value == value.toInt().toFloat()) value.toInt() else value
        publishCustomJson(config, 2, numValue, label = "温度 $value°C")
    }

    /**
     * 设置第 N 路开关（自定义格式下发）type=1
     * @param index 开关序号 1-10
     * @param on true=开启(1), false=关闭(0)
     */
    fun setSwitch(config: DeviceConfig, index: Int, on: Boolean) {
        if (index !in 1..10) return
        val value = if (on) 1 else 0
        publishCustomJson(config, 1, value, index = index, label = "开关$index ${if (on) "开" else "关"}")
    }

    /**
     * 一键控制所有 10 路开关（自定义格式下发）type=5
     * @param on true=全部开启(1), false=全部关闭(0)
     */
    fun setAllSwitches(config: DeviceConfig, on: Boolean) {
        val value = if (on) 1 else 0
        publishCustomJson(config, 5, value, label = "一键${if (on) "开启" else "关闭"}全部开关")
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
     * 归一化 JSON 字符串用于去重比较：
     * 去除小数末尾多余的零（130.0 → 130，130.50 → 130.5），
     * 使数值相同但字符串表示不同的消息被识别为重复。
     */
    private fun normalizeForDedup(payload: String): String {
        if (payload.isEmpty()) return payload
        // 去掉小数末尾的 0：130.50 -> 130.5，130.00 -> 130.
        var result = payload.replace(Regex("\\.(\\d*?)0+(?=[^0-9]|$)"), ".$1")
        // 去掉末尾孤立的小数点：130. -> 130
        result = result.replace(Regex("\\.(?=[^0-9]|$)"), "")
        return result
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

    /**
     * 发布自定义消息到指定 Topic
     * @param topic 完整 Topic，例如 /k1jrhJxxEiu/SJD_app/user/update
     * @param payload 消息内容（字符串，建议 JSON）
     * @param qos 0/1/2
     * @param retained 是否保留消息
     */
    fun publishCustom(topic: String, payload: String, qos: Int = 0, retained: Boolean = false) {
        val client = mqttClient ?: run {
            statusListener?.invoke(Status.ERROR, "未连接，无法发送")
            return
        }
        if (!client.isConnected) {
            statusListener?.invoke(Status.ERROR, "未连接，无法发送")
            return
        }
        Thread {
            try {
                client.publish(topic, payload.toByteArray(Charsets.UTF_8), qos, retained)
                Log.i(TAG, "自定义发送 topic=$topic payload=$payload")
                statusListener?.invoke(Status.CONNECTED, "已发送: $payload")
            } catch (e: Exception) {
                Log.e(TAG, "自定义发送失败: ${e.message}")
                statusListener?.invoke(Status.ERROR, "发送失败: ${e.message}")
            }
        }.start()
    }
}
