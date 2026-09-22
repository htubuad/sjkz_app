package com.iot.control

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
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
        private const val TEMP_PROPERTY = "Temperature"
        const val DEFAULT_DEVICE_ID = "26001_V0.0.0"

        private const val WAKE_LOCK_TAG = "IoTControl:MQTT_WakeLock"
        private const val ALARM_ACTION = "com.iot.control.ACTION_CHECK_MQTT"
        private const val ALARM_INTERVAL_MS = 5 * 60 * 1000L
    }

    @Volatile
    private var mqttClient: MqttAsyncClient? = null

    @Volatile
    private var isConnecting = false

    @Volatile
    private var isPahoReconnecting = false

    @Volatile
    private var lastTopic: String? = null
    @Volatile
    private var lastPayload: String? = null

    private val lastSentValues = java.util.concurrent.ConcurrentHashMap<String, String>()

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var alarmManager: AlarmManager
    private var alarmPendingIntent: PendingIntent? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var isNetworkCallbackRegistered = false
    private var isAlarmReceiverRegistered = false

    @Volatile
    private var lastConfig: DeviceConfig? = null

    /**
     * 设备当前全部参数状态（集中管理）
     * 每次下发时，除了本次操作的值，还会把所有参数的当前值一并发出，
     * 让设备端获得完整状态快照。
     */
    data class DeviceState(
        var temperature: Float = 25f,
        var field1SetValue: Float = 25f,
        var power: Boolean = false,
        var light: Boolean = false,
        var setPoint: Float = 0f,
        var field1Data: Float = 0f,
        var field2Data: Float = 0f,
        var tfState: Int = -1,
        val switches: BooleanArray = BooleanArray(10) { false }
    )

    enum class QueryState { IDLE, QUERYING, COMPLETE, ERROR }

    data class HistoryRecord(val date: String, val t: String, val f1: Float, val f2: Float) {
        val fullTime: String get() = if (date.isNotEmpty() && t.isNotEmpty()) "$date $t" else t
    }

    data class DayPageInfo(var page: Int, var totalPages: Int)

    private val deviceState = DeviceState()

    /** 获取设备当前状态快照（只读副本） */
    fun getDeviceState(): DeviceState = deviceState.copy(switches = deviceState.switches.copyOf())

    /** 用外部持久化的开关状态同步集中状态（App 启动/恢复时调用） */
    fun syncSwitchStates(states: BooleanArray) {
        for (i in 0 until minOf(states.size, 10)) {
            deviceState.switches[i] = states[i]
        }
    }

    /** 从 SharedPreferences 恢复开关状态到集中状态 */
    fun restoreSwitchStatesFromPrefs() {
        val saved = prefs.getString("switch_states", null) ?: return
        val parts = saved.split(",")
        for (i in 0 until minOf(parts.size, 10)) {
            deviceState.switches[i] = parts[i] == "1"
        }
    }

    /** 从 SharedPreferences 恢复电源和灯的状态 */
    fun restorePowerLightFromPrefs() {
        deviceState.power = prefs.getBoolean("power_on", false)
        deviceState.light = prefs.getBoolean("light_on", false)
    }

    /** 保存电源和灯的状态到 SharedPreferences */
    private fun savePowerLightToPrefs() {
        prefs.edit()
            .putBoolean("power_on", deviceState.power)
            .putBoolean("light_on", deviceState.light)
            .apply()
    }

    /** 从 SharedPreferences 恢复设定值 */
    fun restoreSetPointFromPrefs() {
        deviceState.setPoint = prefs.getFloat("set_point", 0f)
    }

    /** 保存设定值到 SharedPreferences */
    private fun saveSetPointToPrefs() {
        prefs.edit().putFloat("set_point", deviceState.setPoint).apply()
    }

    /** 保存 Field1(温度设定) 到 SharedPreferences */
    private fun saveField1ToPrefs() {
        prefs.edit().putFloat("field1_set", deviceState.field1SetValue).apply()
    }

    fun restoreField1FromPrefs() {
        deviceState.field1SetValue = prefs.getFloat("field1_set", 25f)
    }

    /** 保存开关状态到 SharedPreferences（从 ACK 回读后同步持久化） */
    private fun saveSwitchStatesToPrefs() {
        val sb = StringBuilder()
        for (i in 0 until 10) {
            if (i > 0) sb.append(",")
            sb.append(if (deviceState.switches[i]) "1" else "0")
        }
        prefs.edit().putString("switch_states", sb.toString()).apply()
    }

    /**
     * 从 ACK 回执报文中解析设备真实状态并回写到本地集中状态。
     * 以设备回执的实际状态为准（而非控制端下发时的乐观更新），
     * 保证 App 显示与设备真实状态一致。
     *
     * 字段映射（字段名大小写不敏感）:
     * - Switches (int 位掩码) → deviceState.switches（switch1=bit0 ... switch10=bit9）
     * - Temp (double)         → deviceState.temperature（实时环境温度）
     * - Field1 (double)       → deviceState.field1SetValue（温度设定值）
     * - Field2 (double)       → deviceState.setPoint（设定值）
     * - Field1_data (double)  → deviceState.field1Data
     * - Field2_data (double)  → deviceState.field2Data
     * - power (0/1 或 true/false) → deviceState.power
     * - light (0/1 或 true/false) → deviceState.light
     */
    private fun applyStateFromAck(json: org.json.JSONObject) {
        try {
            // 开关位掩码
            val switchesVal = optIgnoreCase(json, "Switches")
            if (switchesVal != null) {
                val bits = toInt(switchesVal)
                for (i in 0 until 10) {
                    deviceState.switches[i] = (bits and (1 shl i)) != 0
                }
            }
            val field1Val = optIgnoreCase(json, "Field1")
            if (field1Val != null) {
                val v = toDouble(field1Val)
                if (!v.isNaN() && v != 0.0) deviceState.field1SetValue = v.toFloat()
            }
            val tempVal = optIgnoreCase(json, "Temp")
            if (tempVal != null) {
                val v = toDouble(tempVal)
                if (!v.isNaN()) deviceState.temperature = v.toFloat()
            }
            // 电源（字段存在时才覆盖）
            val powerVal = optIgnoreCase(json, "power")
            if (powerVal != null) {
                deviceState.power = toBool(powerVal)
            }
            // 灯（字段存在时才覆盖）
            val lightVal = optIgnoreCase(json, "light")
            if (lightVal != null) {
                deviceState.light = toBool(lightVal)
            }
            // 设定值 Field2_data → Field2 → Field1_data → Field2_data 按报文顺序解析
            val f1d = optIgnoreCase(json, "Field1_data")
            if (f1d != null) {
                val v = toDouble(f1d)
                if (!v.isNaN()) deviceState.field1Data = v.toFloat()
            }
            // 设定值 Field2
            val field2Val = optIgnoreCase(json, "Field2")
            if (field2Val != null) {
                val f2 = toDouble(field2Val)
                if (!f2.isNaN()) deviceState.setPoint = f2.toFloat()
            }
            // Field2_data
            val f2d = optIgnoreCase(json, "Field2_data")
            if (f2d != null) {
                val v = toDouble(f2d)
                if (!v.isNaN()) deviceState.field2Data = v.toFloat()
            }
            // 同步发送端去重缓存，避免回读后的值与缓存不一致导致下次同值下发被跳过
            refreshLastSentValuesFromState()
            // 持久化
            savePowerLightToPrefs()
            saveSwitchStatesToPrefs()
            saveSetPointToPrefs()
            // TF 卡状态
            extractAndUpdateTfState(json)
            Log.i(TAG, "从ACK回读状态: " +
                    "switches=${deviceState.switches.joinToString("") { if (it) "1" else "0" }} " +
                    "temp=${deviceState.temperature} " +
                    "setPoint=${deviceState.setPoint} " +
                    "Field1_data=${deviceState.field1Data} " +
                    "Field2_data=${deviceState.field2Data} " +
                    "power=${deviceState.power} light=${deviceState.light}")
        } catch (e: Exception) {
            Log.w(TAG, "解析ACK状态失败: ${e.message}")
        }
    }

    /** 按大小写不敏感的方式取 JSON 字段值 */
    private fun optIgnoreCase(json: org.json.JSONObject, key: String): Any? {
        if (json.has(key)) return json.opt(key)
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.equals(key, ignoreCase = true)) return json.opt(k)
        }
        return null
    }

    /** 把 JSON 值转为 Int（兼容数字、字符串） */
    private fun toInt(value: Any): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: 0
        is Boolean -> if (value) 1 else 0
        else -> 0
    }

    /** 把 JSON 值转为 Double（兼容数字、字符串） */
    private fun toDouble(value: Any): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: Double.NaN
        is Boolean -> if (value) 1.0 else 0.0
        else -> Double.NaN
    }

    /** 把 JSON 值转为 Boolean（兼容 0/1、"0"/"1"、true/false） */
    private fun toBool(value: Any): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value == "1" || value.equals("true", ignoreCase = true)
        else -> false
    }

    /**
     * 按当前 deviceState 刷新发送端去重缓存，保证回读后的值与缓存一致，
     * 避免 ACK 回读改变状态后，再次下发相同值被去重逻辑错误跳过。
     */
    private fun refreshLastSentValuesFromState() {
        // 电源 type=3
        lastSentValues["3_0"] = if (deviceState.power) "1" else "0"
        // 灯 type=4
        lastSentValues["4_0"] = if (deviceState.light) "1" else "0"
        // 温度 type=2（整数温度以整数形式）
        val t = deviceState.field1SetValue
        lastSentValues["2_0"] = if (t == t.toInt().toFloat()) t.toInt().toString() else t.toString()
        // 设定值 type=6
        val sp = deviceState.setPoint
        lastSentValues["6_0"] = if (sp == sp.toInt().toFloat()) sp.toInt().toString() else sp.toString()
        // 开关 type=1（每路）
        for (i in 0 until 10) {
            lastSentValues["1_${i + 1}"] = if (deviceState.switches[i]) "1" else "0"
        }
    }

    /**
     * 发送前状态快照：保存下发命令前的 deviceState，
     * 若 ACK 超时失败，则恢复到此快照，回滚各按钮状态和设定值。
     */
    @Volatile
    private var preSendSnapshot: DeviceState? = null

    /** 在 set* 方法修改 deviceState 之前调用，保存发送前状态快照 */
    private fun savePreSendSnapshot() {
        preSendSnapshot = deviceState.copy(switches = deviceState.switches.copyOf())
    }

    /**
     * 发送失败（ACK 超时）时恢复到发送前状态：
     * 回滚 deviceState 各字段 → 持久化 → 刷新去重缓存 → 通知 UI 刷新
     */
    private fun restoreFromSnapshotAndNotify(label: String) {
        val snap = preSendSnapshot
        if (snap != null) {
            deviceState.temperature = snap.temperature
            deviceState.field1SetValue = snap.field1SetValue
            deviceState.power = snap.power
            deviceState.light = snap.light
            deviceState.setPoint = snap.setPoint
            deviceState.field1Data = snap.field1Data
            deviceState.field2Data = snap.field2Data
            for (i in 0 until 10) deviceState.switches[i] = snap.switches[i]
            // 持久化回滚后的状态
            savePowerLightToPrefs()
            saveSwitchStatesToPrefs()
            saveSetPointToPrefs()
            // 刷新发送端去重缓存，使下次下发不被旧值跳过
            refreshLastSentValuesFromState()
            preSendSnapshot = null
            Log.i(TAG, "发送失败，已回滚状态: $label")
        }
        // 通知 UI 刷新（从回滚后的 deviceState 读取）
        ackListener?.invoke(false, label)
    }

    /** ACK 成功时清除快照（设备已确认，无需回滚） */
    private fun clearPreSendSnapshot() {
        preSendSnapshot = null
    }

    /**
     * 标记用户是否主动断开过连接（持久化到 SharedPreferences，App 重启后仍有效）
     * - true: 主动断开过，应用启动时不自动重连，必须用户再次点击连接
     * - false: 未主动断开过（如意外掉线），保持自动重连
     */
    private val prefs by lazy {
        context.getSharedPreferences("iot_config", android.content.Context.MODE_PRIVATE)
    }

    init {
        restoreSwitchStatesFromPrefs()
        restorePowerLightFromPrefs()
        restoreSetPointFromPrefs()
        restoreField1FromPrefs()

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply { setReferenceCounted(false) }

        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallback()
        registerAlarmReceiver()
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
    var sentPayloadListener: ((String, String) -> Unit)? = null
    var ackListener: ((Boolean, String) -> Unit)? = null
    var logListener: ((String) -> Unit)? = null

    @Volatile
    var lastDevicePacketTimeMs: Long = 0L
        private set
    @Volatile
    var hasReceivedDevicePacket: Boolean = false
        private set
    data class TempRecord(val value: Float, val timestamp: Long = System.currentTimeMillis())

    /** TF 卡状态变化监听：tfState=-1 未收到, 1=正常, 0=异常 */
    var tfStateListener: ((Int) -> Unit)? = null
    /** 温度历史监听：收到新的 Field1_data 并记录后触发 */
    var temperatureHistoryListener: (() -> Unit)? = null
    /** 温度曲线2历史监听：收到新的 Field2_data 并记录后触发 */
    var temperature2HistoryListener: (() -> Unit)? = null

    /** 历史查询回调：(QueryState, 累计记录列表, 错误原因) */
    var queryResultListener: ((QueryState, List<HistoryRecord>, String?) -> Unit)? = null

    private val queryBuffer = mutableListOf<HistoryRecord>()
    private val MAX_QUERY_RECORDS = 20000
    private val queryDayPages = mutableMapOf<String, DayPageInfo>()
    @Volatile private var queryState = QueryState.IDLE
    @Volatile private var queryStartDate = ""
    @Volatile private var queryStartTime = ""
    @Volatile private var queryEndDate = ""
    @Volatile private var queryEndTime = ""
    @Volatile private var queryTotalCount = 0

    private val temperatureHistory = java.util.concurrent.CopyOnWriteArrayList<TempRecord>()
    private val maxTemperatureHistory = 1000
    private val temperature2History = java.util.concurrent.CopyOnWriteArrayList<TempRecord>()
    private val maxTemperature2History = 1000

    fun getTemperatureHistory(): List<Float> = temperatureHistory.map { it.value }
    fun getTemperatureHistoryRecords(): List<TempRecord> = temperatureHistory.toList()

    fun clearTemperatureHistory() {
        temperatureHistory.clear()
        temperatureHistoryListener?.invoke()
    }

    fun getTemperature2History(): List<Float> = temperature2History.map { it.value }
    fun getTemperature2HistoryRecords(): List<TempRecord> = temperature2History.toList()

    fun clearTemperature2History() {
        temperature2History.clear()
        temperature2HistoryListener?.invoke()
    }

    fun getTfState(): Int = deviceState.tfState

    fun getQueryState(): QueryState = queryState

    fun getQueryBuffer(): List<HistoryRecord> = queryBuffer.toList()
    fun getQueryTotalCount(): Int = queryTotalCount
    fun getQueryDayPages(): Map<String, DayPageInfo> = queryDayPages.toMap()
    fun queryHistoryRange(startDate: String, startTime: String, endDate: String, endTime: String) {
        if (queryState == QueryState.QUERYING) {
            Log.w(TAG, "已有查询进行中，忽略新请求")
            queryResultListener?.invoke(QueryState.ERROR, emptyList(), "查询进行中，请稍后")
            return
        }
        if (mqttClient?.isConnected != true) {
            Log.w(TAG, "未连接，无法查询历史数据")
            queryResultListener?.invoke(QueryState.ERROR, emptyList(), "未连接")
            return
        }
        val cfg = lastConfig
        if (cfg == null) {
            queryResultListener?.invoke(QueryState.ERROR, emptyList(), "未配置")
            return
        }
        if (startDate.isBlank() || endDate.isBlank()) {
            queryResultListener?.invoke(QueryState.ERROR, emptyList(), "日期无效")
            return
        }
        queryStartDate = startDate
        queryStartTime = normalizeTime(startTime, "00:00:00")
        queryEndDate = endDate
        queryEndTime = normalizeTime(endTime, "23:59:59")
        queryBuffer.clear()
        queryDayPages.clear()
        queryTotalCount = 0
        queryState = QueryState.QUERYING
        sendQuery(cfg)
    }

    private fun normalizeTime(raw: String, default: String): String {
        if (raw.isBlank()) return default
        val parts = raw.split(":")
        return when (parts.size) {
            2 -> "%02d:%02d:00".format(parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0)
            3 -> raw
            else -> default
        }
    }

    private fun sendQuery(cfg: DeviceConfig) {
        val deviceId = prefs.getString("device_id", DEFAULT_DEVICE_ID) ?: DEFAULT_DEVICE_ID
        val payload = """{"DeviceID":"$deviceId","Dir":"C>D","Cmd":"QUERY","StartDate":"$queryStartDate","StartTime":"$queryStartTime","EndDate":"$queryEndDate","EndTime":"$queryEndTime"}"""
        val topic = "/${cfg.productKey}/${cfg.deviceName}/user/update"
        try {
            mqttClient?.publish(topic, payload.toByteArray(Charsets.UTF_8), 0, false)
            Log.i(TAG, "发送历史查询: $payload")
            logListener?.invoke("TX QUERY $queryStartDate $queryStartTime ~ $queryEndDate $queryEndTime")
            sentPayloadListener?.invoke(topic, payload)
            queryResultListener?.invoke(QueryState.QUERYING, queryBuffer.toList(), null)
        } catch (e: Exception) {
            Log.e(TAG, "查询发送异常", e)
            queryState = QueryState.ERROR
            queryResultListener?.invoke(QueryState.ERROR, queryBuffer.toList(), e.message)
        }
    }

    fun cancelQuery() {
        if (queryState != QueryState.QUERYING) return
        try {
            val deviceId = prefs.getString("device_id", DEFAULT_DEVICE_ID) ?: DEFAULT_DEVICE_ID
            val cfg = lastConfig
            if (cfg != null) {
                val payload = """{"DeviceID":"$deviceId","Dir":"C>D","Cmd":"QUERY_CANCEL"}"""
                val topic = "/${cfg.productKey}/${cfg.deviceName}/user/update"
                mqttClient?.publish(topic, payload.toByteArray(Charsets.UTF_8), 0, false)
                Log.i(TAG, "发送查询取消: $payload")
                logListener?.invoke("TX QUERY_CANCEL")
                sentPayloadListener?.invoke(topic, payload)
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送取消异常", e)
        }
        queryState = QueryState.IDLE
        queryBuffer.clear()
        queryDayPages.clear()
        queryStartDate = ""
        queryStartTime = ""
        queryEndDate = ""
        queryEndTime = ""
        queryTotalCount = 0
        queryResultListener?.invoke(QueryState.IDLE, emptyList(), null)
    }

    private fun handleQueryDataPacket(json: org.json.JSONObject) {
        if (queryState != QueryState.QUERYING) {
            return
        }
        val cmd = json.optString("Cmd", "")
        when (cmd) {
            "QUERY_DATA" -> {
                try {
                    val queryDate = json.optString("QueryDate", "")
                    val page = json.optInt("Page", 0)
                    val totalPages = json.optInt("TotalPages", 0)
                    val records = json.optJSONArray("Records")

                    val info = queryDayPages.getOrPut(queryDate) { DayPageInfo(page, totalPages) }
                    info.page = page
                    info.totalPages = totalPages

                    if (page == 0) {
                        val removed = queryBuffer.removeAll { it.date == queryDate }
                        if (removed) {
                            Log.d(TAG, "QUERY_DATA Page=0 替换 $queryDate (移除旧数据)")
                        }
                    }

                    val recordsThisPage = records?.length() ?: 0
                    if (records != null) {
                        for (i in 0 until records.length()) {
                            if (queryBuffer.size >= MAX_QUERY_RECORDS) {
                                Log.w(TAG, "超出 MAX_QUERY_RECORDS($MAX_QUERY_RECORDS)，截断")
                                break
                            }
                            val r = records.getJSONObject(i)
                            val d = r.optString("date", queryDate)
                            val t = r.optString("t", "")
                            val f1 = r.optDouble("f1", Double.NaN).toFloat()
                            val f2 = r.optDouble("f2", Double.NaN).toFloat()
                            queryBuffer.add(HistoryRecord(d, t, f1, f2))
                        }
                    }

                    val dayLabel = if (queryDate.isNotEmpty()) queryDate else "?"
                    val pageLabel = if (totalPages > 0) "${page + 1}/$totalPages" else "${page + 1}"
                    logListener?.invoke("RX QUERY_DATA $dayLabel $pageLabel ($recordsThisPage records)")
                    Log.d(TAG, "QUERY_DATA 累计 ${queryBuffer.size} 条 (${queryDayPages.size} 天)")
                    queryResultListener?.invoke(QueryState.QUERYING, queryBuffer.toList(), null)
                } catch (e: Exception) {
                    Log.e(TAG, "解析 QUERY_DATA 失败", e)
                }
            }
            "QUERY_END" -> {
                val recordCount = json.optInt("RecordCount", 0)
                queryTotalCount = recordCount
                val actual = queryBuffer.size
                val daysInfo = queryDayPages.keys.joinToString(",")
                if (recordCount > 0 && actual < recordCount) {
                    Log.w(TAG, "丢包警告: 收到 $actual < 设备声明 $recordCount")
                }
                logListener?.invoke("RX QUERY_END 累计 $actual / $recordCount 条 (${queryDayPages.size} 天: $daysInfo)")
                Log.i(TAG, "查询完成: $queryStartDate $queryStartTime ~ $queryEndDate $queryEndTime 共 $actual 条 (设备声明=$recordCount, ${queryDayPages.size} 天)")
                queryState = QueryState.COMPLETE
                queryResultListener?.invoke(QueryState.COMPLETE, queryBuffer.toList(), null)
            }
            "QUERY_ERROR" -> {
                val reason = json.optString("Reason", "UNKNOWN")
                logListener?.invoke("RX QUERY_ERROR reason=$reason")
                Log.w(TAG, "查询失败: reason=$reason")
                queryState = QueryState.ERROR
                queryResultListener?.invoke(QueryState.ERROR, queryBuffer.toList(), reason)
            }
        }
    }

    private fun extractAndUpdateTfState(json: org.json.JSONObject) {
        val tfVal = optIgnoreCase(json, "TF_state")
        if (tfVal != null) {
            val newState = toInt(tfVal)
            if (newState != deviceState.tfState) {
                deviceState.tfState = newState
                tfStateListener?.invoke(newState)
                Log.i(TAG, "TF卡状态更新: ${if (newState == 1) "正常" else "异常"} (TF_state=$newState)")
            }
        }
    }

    private fun recordTemperatureFromPayload(payload: String) {
        try {
            val json = org.json.JSONObject(payload)
            extractAndUpdateTfState(json)
            val ts = parseDeviceTimestamp(json.optString("date", ""), json.optString("t", ""))
                ?: System.currentTimeMillis()
            val f1d = optIgnoreCase(json, "Field1_data") ?: optIgnoreCase(json, "f1")
            if (f1d != null) {
                val v = toDouble(f1d)
                if (!v.isNaN() && v != 0.0) addTemperaturePoint(v.toFloat(), ts)
            }
            val f2d = optIgnoreCase(json, "Field2_data") ?: optIgnoreCase(json, "f2")
            if (f2d != null) {
                val v = toDouble(f2d)
                if (!v.isNaN() && v != 0.0) addTemperature2Point(v.toFloat(), ts)
            }
        } catch (_: Exception) {}
    }

    private val tsFmtFull = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
    private val tsFmtTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    private fun parseDeviceTimestamp(date: String, t: String): Long? {
        return try {
            when {
                date.isNotEmpty() && t.isNotEmpty() -> tsFmtFull.parse("$date $t")?.time
                t.length == 8 -> tsFmtTime.parse(t)?.let {
                    val cal = java.util.Calendar.getInstance()
                    val tcal = java.util.Calendar.getInstance()
                    tcal.timeInMillis = it.time
                    cal.set(java.util.Calendar.HOUR_OF_DAY, tcal.get(java.util.Calendar.HOUR_OF_DAY))
                    cal.set(java.util.Calendar.MINUTE, tcal.get(java.util.Calendar.MINUTE))
                    cal.set(java.util.Calendar.SECOND, tcal.get(java.util.Calendar.SECOND))
                    cal.timeInMillis
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    fun addTemperaturePoint(value: Float, timestamp: Long = System.currentTimeMillis()) {
        if (value < -100f || value > 200f) return
        temperatureHistory.add(TempRecord(value, timestamp))
        if (temperatureHistory.size > maxTemperatureHistory) {
            temperatureHistory.removeAt(0)
        }
        temperatureHistoryListener?.invoke()
    }

    fun addTemperature2Point(value: Float, timestamp: Long = System.currentTimeMillis()) {
        if (value < -100f || value > 200f) return
        temperature2History.add(TempRecord(value, timestamp))
        if (temperature2History.size > maxTemperature2History) {
            temperature2History.removeAt(0)
        }
        temperature2HistoryListener?.invoke()
    }

    // 待确认回执的命令信息
    @Volatile
    private var pendingAckLabel: String? = null
    @Volatile
    private var pendingAckDeviceId: String? = null
    private var pendingAckTimer: java.util.Timer? = null

    /**
     * 显示回执弹窗（统一用 Application context，任何页面都能显示）
     * - success: 绿色背景 + 成功
     * - fail:    红色背景 + 失败
     * 必须在主线程创建并显示 Toast（带自定义 view 时尤为关键），
     * 因此用 Handler 投递到主 Looper。
     */
    private fun showAckToast(text: String, success: Boolean) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            try {
                val toastView = android.view.LayoutInflater.from(context)
                    .inflate(R.layout.toast_ack, null)
                toastView.findViewById<android.widget.TextView>(R.id.tvToastText).text = text
                toastView.findViewById<android.view.View>(R.id.toastContainer)
                    .setBackgroundResource(if (success) R.drawable.toast_bg_success else R.drawable.toast_bg_fail)
                val toast = Toast(context)
                toast.apply {
                    // 用 LENGTH_LONG 作为基础（约3.5s），再用 Handler 在1秒后取消，实现精确1秒显示
                    duration = Toast.LENGTH_LONG
                    view = toastView
                    // 靠下显示，避免遮挡屏幕中央内容
                    val yOffset = (120 * context.resources.displayMetrics.density).toInt()
                    setGravity(android.view.Gravity.BOTTOM, 0, yOffset)
                }
                toast.show()
                // 1秒后取消显示
                mainHandler.postDelayed({ toast.cancel() }, 1000)
                Log.i(TAG, "回执弹窗已显示(1秒): $text")
            } catch (e: Exception) {
                Log.w(TAG, "显示回执弹窗失败: ${e.message}")
            }
        }
    }

    /**
     * 安全清理旧 MQTT 客户端：
     * 1. 先移除回调，防止 connectionLost/connectComplete 触发导致状态反复跳变
     * 2. 用 disconnectForcibly 强制断开（同时停止自动重连）
     * 3. 再关闭客户端
     */
    private fun cleanupOldClient() {
        val old = mqttClient ?: return
        try {
            // 移除回调，防止 disconnect/close 时 connectionLost 回调
            // 把状态改成 DISCONNECTED，造成 UI 闪烁/反复断开
            old.setCallback(null)
        } catch (_: Exception) {}
        try {
            // disconnectForcibly 会强制断开并停止自动重连
            if (old.isConnected) {
                old.disconnectForcibly()
            }
        } catch (_: Exception) {}
        try {
            old.close(true)
        } catch (_: Exception) {}
        mqttClient = null
    }

    /**
     * 连接阿里云 IoT（在后台线程执行）
     */
    fun connect(config: DeviceConfig) {
        if (config.productKey.isBlank() || config.deviceName.isBlank() || config.deviceSecret.isBlank()) {
            statusListener?.invoke(Status.ERROR, "设备三元组不能为空")
            return
        }

        // 已在连接中 → 不重复发起，避免并发连接导致反复断开重连
        if (isConnecting) {
            Log.i(TAG, "已在连接中，跳过重复连接请求")
            return
        }
        // 已经连上 → 不重复连接
        if (isConnected()) {
            Log.i(TAG, "已连接，跳过重复连接请求")
            statusListener?.invoke(Status.CONNECTED, "已连接到阿里云 IoT")
            return
        }

        isConnecting = true
        lastConfig = config

        setManualDisconnected(false)

        statusListener?.invoke(Status.CONNECTING, "正在连接阿里云 IoT…")

        // 在后台线程执行连接，避免 NetworkOnMainThreadException
        Thread {
            try {
                connectInternal(config)
            } catch (e: Exception) {
                Log.e(TAG, "连接异常", e)
                statusListener?.invoke(Status.ERROR, "连接异常: ${e.message ?: "未知错误"}")
            } finally {
                isConnecting = false
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

        // 安全清理旧连接（禁用重连 + 移除回调 + 断开关闭）
        cleanupOldClient()

        val client = MqttAsyncClient(broker, mqttClientId, MemoryPersistence())

        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                // 忽略已废弃的旧客户端回调
                if (mqttClient !== client) {
                    Log.w(TAG, "忽略旧客户端的 connectComplete 回调")
                    return
                }
                Log.i(TAG, "MQTT 连接成功 (reconnect=$reconnect)")
                isPahoReconnecting = false
                hasReceivedDevicePacket = false
                lastDevicePacketTimeMs = 0L
                statusListener?.invoke(Status.CONNECTED, "已连接到阿里云 IoT")
                acquireWakeLock()
                startAlarmCheck()
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
                        client.subscribe(topic, 1)
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
                        client.subscribe(customTopic, 1)
                        Log.i(TAG, "已订阅自定义Topic: $customTopic")
                    } catch (e: Exception) {
                        Log.e(TAG, "订阅自定义Topic失败 $customTopic: ${e.message}")
                    }
                }
            }

            override fun connectionLost(cause: Throwable?) {
                if (mqttClient !== client) {
                    Log.w(TAG, "忽略旧客户端的 connectionLost 回调: ${cause?.message}")
                    return
                }
                Log.w(TAG, "MQTT 连接丢失: ${cause?.message}")
                isPahoReconnecting = true
                startAlarmCheck()
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
                // 自动匹配设备号版本：收到任何带 DeviceID 的消息时，
                // 若设备号前缀(下划线前)与当前配置一致，采用收到的完整 DeviceID
                // （含设备真实版本号），使后续发送与接收的 DeviceID 完全一致
                try {
                    val recvJson = org.json.JSONObject(payload)
                    val recvDevId = recvJson.optString("DeviceID", "")
                    if (recvDevId.contains('_')) {
                        val recvPrefix = recvDevId.substringBefore('_')
                        val storedId = prefs.getString("device_id", DEFAULT_DEVICE_ID) ?: DEFAULT_DEVICE_ID
                        val storedPrefix = storedId.substringBefore('_')
                        if (recvPrefix == storedPrefix && recvDevId != storedId) {
                            prefs.edit().putString("device_id", recvDevId).apply()
                            Log.i(TAG, "设备号版本已自动匹配: $storedId → $recvDevId")
                        }
                    }
                } catch (_: Exception) {}
                // 收到平台下发的 property/set 指令时，自动回复 set_reply
                if (topic?.endsWith("thing/service/property/set") == true) {
                    replyToPropertySet(config, payload)
                }
                // 检测回执报文（Dir="ACK"）
                try {
                    val recvJson = org.json.JSONObject(payload)
                    val recvDir = recvJson.optString("Dir", "")
                    val recvDevId = recvJson.optString("DeviceID", "")
                    val recvPrefix = recvDevId.substringBefore('_', "")
                    val storedId = prefs.getString("device_id", DEFAULT_DEVICE_ID) ?: DEFAULT_DEVICE_ID
                    val storedPrefix = storedId.substringBefore('_')
                    val isMyDevice = recvPrefix.isEmpty() || recvPrefix == storedPrefix
                    if (isMyDevice && (recvDir == "ACK" || recvDir == "D>C")) {
                        lastDevicePacketTimeMs = System.currentTimeMillis()
                        hasReceivedDevicePacket = true
                    }
                    if (recvDir == "ACK") {
                        if (isMyDevice) {
                            applyStateFromAck(recvJson)
                        }
                        val label = pendingAckLabel
                        val pendingPrefix = pendingAckDeviceId?.substringBefore('_', "")
                        val idMatched = pendingAckDeviceId == null ||
                                pendingPrefix.isNullOrEmpty() ||
                                recvPrefix == pendingPrefix
                        Log.i(TAG, "收到回执 Dir=ACK deviceId=$recvDevId prefix=$recvPrefix label=$label pendingId=$pendingAckDeviceId pendingPrefix=$pendingPrefix matched=$idMatched isMyDevice=$isMyDevice")
                        if (label != null && idMatched) {
                            pendingAckLabel = null
                            pendingAckDeviceId = null
                            pendingAckTimer?.cancel()
                            pendingAckTimer = null
                            clearPreSendSnapshot()
                            showAckToast("成功\n$label", true)
                        }
                        if (isMyDevice) {
                            ackListener?.invoke(true, label ?: "状态同步")
                        }
                    } else if (recvDir == "D>C") {
                        val cmd = recvJson.optString("Cmd", "")
                        if (cmd == "QUERY_DATA" || cmd == "QUERY_END" || cmd == "QUERY_ERROR") {
                            handleQueryDataPacket(recvJson)
                        }
                    }
                } catch (_: Exception) {}
                recordTemperatureFromPayload(payload)
                messageListener?.invoke(topicStr, payload)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            keepAliveInterval = 30
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
        isConnecting = false
        deviceState.tfState = -1
        tfStateListener?.invoke(-1)
        val oldClient = mqttClient
        mqttClient = null
        statusListener?.invoke(Status.DISCONNECTED, "已断开连接")
        Thread {
            releaseWakeLock()
            stopAlarmCheck()
            IoTConnectionService.stop(context)
            oldClient?.let { client ->
                try { client.setCallback(null) } catch (_: Exception) {}
                try { if (client.isConnected) client.disconnectForcibly() } catch (_: Exception) {}
                try { client.close(true) } catch (_: Exception) {}
            }
        }.start()
    }

    /**
     * 自定义下发 Topic: /{productKey}/{deviceName}/user/update
     */
    private fun customTopic(config: DeviceConfig): String =
        "/${config.productKey}/${config.deviceName}/user/update"

    /**
     * 构造标准报文（统一模板入口）。
     *
     * 标准格式: {"DeviceID":"26001_V0.0.0","Dir":"C>D","power":0,"light":1,"Switches":0,"Field1":0.00,"Field2":0.00,"Field1_data":0.00,"Field2_data":0.00}
     *
     * 字段说明:
     * - DeviceID: 设备号前缀由设置页选择，版本号由设备端回执提供（未收到时为0.0.0）
     * - Dir: 报文方向（C>D=控制端发送, D>C=设备发送, ACK=回执）
     * - power: 电源状态 0/1
     * - light: 灯状态 0/1
     * - Switches: 10路开关位掩码（整数），switch 1 = bit 0 (LSB)，switch 10 = bit 9
     * - Field1: 温度值（2位小数）
     * - Field2: 单路开关时为序号(1~10)，其他操作为设定值(setPoint)
     * - Field1_data / Field2_data: 由设备端回填，控制端固定发 0.00
     *
     * @param deviceId 设备号，默认从 SharedPreferences 读取
     * @param field2  Field2 值，传 null 时按 typeCode 决定（单路开关=序号，其他=设定值）
     * @param typeCode 操作类型编码（用于 field2 默认值计算）
     * @param index   单路开关序号（typeCode=1 时有效）
     */
    fun buildStandardPayload(
        deviceId: String = prefs.getString("device_id", DEFAULT_DEVICE_ID) ?: DEFAULT_DEVICE_ID,
        field2: String? = null,
        typeCode: Int = 0,
        index: Int = 0
    ): String {
        // 10 路开关按位打包为整数：switch 1 = bit 0 (LSB)，switch 10 = bit 9
        var switchBits = 0
        for (i in 0 until 10) {
            if (deviceState.switches[i]) switchBits = switchBits or (1 shl i)
        }
        val f1 = "%.2f".format(deviceState.field1SetValue)
        // Field2 = 单路开关时为序号，其他操作为设定值(setPoint)
        val f2 = field2 ?: if (typeCode == 1 && index > 0) "%.2f".format(index.toFloat()) else "%.2f".format(deviceState.setPoint)
        // 字段顺序: Field1 → Field1_data → Field2 → Field2_data
        return """{"DeviceID":"$deviceId","Dir":"C>D","power":${if (deviceState.power) 1 else 0},"light":${if (deviceState.light) 1 else 0},"Switches":$switchBits,"Field1":$f1,"Field1_data":0.00,"Field2":$f2,"Field2_data":0.00}"""
    }

    /**
     * type 编码: 1=switch 单路开关, 2=temperature 温度, 3=power 电源, 4=light 灯, 5=switch_all 全控, 6=setPoint 设定值
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

        // 发送端去重：值与上次下发相同则跳过
        val dedupKey = "${typeCode}_$index"
        val valueStr = value.toString()
        val lastValue = lastSentValues[dedupKey]
        if (lastValue != null && lastValue == valueStr) {
            Log.d(TAG, "值未变化，跳过下发[$label]: type=$typeCode index=$index value=$value")
            return
        }

        val topic = customTopic(config)
        val deviceId = prefs.getString("device_id", DEFAULT_DEVICE_ID) ?: DEFAULT_DEVICE_ID
        val payload = buildStandardPayload(deviceId = deviceId, typeCode = typeCode, index = index)

        Thread {
            try {
                client.publish(topic, payload.toByteArray(Charsets.UTF_8), 0, false)
                lastSentValues[dedupKey] = valueStr
                Log.i(TAG, "下发[$label]: $topic $payload")
                // 通知 UI 显示控制端发送的原始报文（便于分析）
                sentPayloadListener?.invoke(topic, payload)
                // 设置待确认回执
                pendingAckLabel = label
                pendingAckDeviceId = deviceId
                pendingAckTimer?.cancel()
                pendingAckTimer = java.util.Timer().apply {
                    schedule(object : java.util.TimerTask() {
                        override fun run() {
                            val l = pendingAckLabel
                            if (l != null) {
                                pendingAckLabel = null
                                pendingAckDeviceId = null
                                pendingAckTimer = null
                                // 统一在 manager 显示超时弹窗（任何页面都生效）
                                showAckToast("失败\n$l", false)
                                // 回滚状态到发送前快照，并通知 UI 刷新
                                restoreFromSnapshotAndNotify(l)
                            }
                        }
                    }, 10000) // 10秒超时
                }
                statusListener?.invoke(Status.CONNECTED, "发送中 $label...")
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
        savePreSendSnapshot()
        deviceState.power = on
        savePowerLightToPrefs()
        val value = if (on) 1 else 0
        publishCustomJson(config, 3, value, label = "电源开关 ${if (on) "开" else "关"}")
    }

    /**
     * 灯开关（自定义格式下发）type=4
     */
    fun setLight(config: DeviceConfig, on: Boolean) {
        savePreSendSnapshot()
        deviceState.light = on
        savePowerLightToPrefs()
        val value = if (on) 1 else 0
        publishCustomJson(config, 4, value, label = "灯开关 ${if (on) "开" else "关"}")
    }

    /**
     * 温度设置（自定义格式下发）type=2
     * @param value 温度值（支持浮点数）
     */
    fun setTemperature(config: DeviceConfig, value: Float) {
        savePreSendSnapshot()
        deviceState.field1SetValue = value
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
        savePreSendSnapshot()
        deviceState.switches[index - 1] = on
        val value = if (on) 1 else 0
        publishCustomJson(config, 1, value, index = index, label = "开关$index ${if (on) "开" else "关"}")
    }

    /**
     * 一键控制所有 10 路开关（自定义格式下发）type=5
     * @param on true=全部开启(1), false=全部关闭(0)
     */
    fun setAllSwitches(config: DeviceConfig, on: Boolean) {
        savePreSendSnapshot()
        for (i in 0 until 10) deviceState.switches[i] = on
        val value = if (on) 1 else 0
        publishCustomJson(config, 5, value, label = "一键${if (on) "开启" else "关闭"}全部开关")
    }

    /**
     * 设置设定值（自定义格式下发）type=6
     * 下发报文中 Field2 = 设定值
     * @param value 设定值（浮点数）
     */
    fun setSetPoint(config: DeviceConfig, value: Float) {
        savePreSendSnapshot()
        deviceState.setPoint = value
        saveSetPointToPrefs()
        val numValue: Number = if (value == value.toInt().toFloat()) value.toInt() else value
        publishCustomJson(config, 6, numValue, label = "设定值 $value")
    }

    /**
     * 设置 Field1（温度设定）type=2
     * 下发报文中 Field1 = 新温度值，Field2 保持当前设定值
     * @param value 温度设定值（浮点数）
     */
    fun setField1(config: DeviceConfig, value: Float) {
        savePreSendSnapshot()
        deviceState.field1SetValue = value
        saveField1ToPrefs()
        val numValue: Number = if (value == value.toInt().toFloat()) value.toInt() else value
        publishCustomJson(config, 2, numValue, label = "温度设定 $value")
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

    private fun acquireWakeLock() {
        try {
            if (!wakeLock.isHeld) {
                wakeLock.acquire()
                Log.i(TAG, "WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "acquireWakeLock failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.i(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "releaseWakeLock failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAlarmCheck() {
        stopAlarmCheck()
        try {
            val intent = Intent(context, MqttCheckAlarmReceiver::class.java)
                .setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
            alarmPendingIntent = pi
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                    pi
                )
            } else {
                alarmManager.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                    ALARM_INTERVAL_MS,
                    pi
                )
            }
            Log.i(TAG, "AlarmManager check scheduled (interval=${ALARM_INTERVAL_MS}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "startAlarmCheck failed", e)
        }
    }

    private fun stopAlarmCheck() {
        try {
            alarmPendingIntent?.let { alarmManager.cancel(it) }
            alarmPendingIntent = null
            Log.i(TAG, "AlarmManager check cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "stopAlarmCheck failed", e)
        }
    }

    private fun registerNetworkCallback() {
        if (isNetworkCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network available, checking MQTT...")
                    ensureConnectedIfLost()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    Log.d(TAG, "Network caps changed, hasInternet=$hasInternet")
                    if (hasInternet) ensureConnectedIfLost()
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Network lost")
                }
            }
            connectivityManager.registerNetworkCallback(request, cb)
            isNetworkCallbackRegistered = true
            Log.i(TAG, "NetworkCallback registered")
        } catch (e: Exception) {
            Log.e(TAG, "registerNetworkCallback failed", e)
        }
    }

    private fun registerAlarmReceiver() {
        if (isAlarmReceiverRegistered) return
        try {
            val filter = IntentFilter(ALARM_ACTION)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == ALARM_ACTION) {
                        Log.i(TAG, "AlarmManager triggered: check MQTT")
                        ensureConnectedIfLost()
                    }
                }
            }, filter)
            isAlarmReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "registerAlarmReceiver failed", e)
        }
    }

    private fun ensureConnectedIfLost() {
        if (isConnected()) {
            Log.d(TAG, "MQTT still connected, skip reconnect")
            return
        }
        if (isManualDisconnected()) {
            Log.i(TAG, "User manually disconnected, skip auto reconnect")
            return
        }
        val cfg = lastConfig
        if (cfg == null) {
            Log.w(TAG, "No lastConfig saved, cannot auto reconnect")
            return
        }
        if (isConnecting) {
            Log.i(TAG, "Already connecting, skip")
            return
        }
        if (isPahoReconnecting) {
            Log.d(TAG, "Paho is auto-reconnecting, skip manual intervention")
            return
        }
        Log.i(TAG, "Auto reconnecting via ensureConnectedIfLost()")
        Thread {
            try {
                connectInternal(cfg)
            } catch (e: Exception) {
                Log.e(TAG, "ensureConnectedIfLost reconnect failed", e)
            }
        }.start()
    }

    class MqttCheckAlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ALARM_ACTION) {
                val app = context.applicationContext as? IoTApp ?: return
                val manager = app.iotManager
                manager.ensureConnectedIfLostFromAlarm()
            }
        }
    }

    internal fun ensureConnectedIfLostFromAlarm() {
        try {
            ensureConnectedIfLost()
            if (isConnected()) {
                startAlarmCheck()
            } else {
                Log.w(TAG, "Alarm check: still not connected after attempt")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureConnectedIfLostFromAlarm failed", e)
        }
    }

}