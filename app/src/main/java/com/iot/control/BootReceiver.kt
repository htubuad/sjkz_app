package com.iot.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val DELAY_MS = 15000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "收到广播: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> scheduleConnect(context)
        }
    }

    private fun scheduleConnect(context: Context) {
        val pendingResult = goAsync()

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val app = context.applicationContext as IoTApp
                val manager = app.iotManager
                val prefs = context.getSharedPreferences("iot_config", Context.MODE_PRIVATE)

                val pk = prefs.getString("productKey", "") ?: ""
                val dn = prefs.getString("deviceName", "") ?: ""
                val ds = prefs.getString("deviceSecret", "") ?: ""
                val manualDisconnect = prefs.getBoolean("user_manual_disconnect", false)

                if (pk.isBlank() || dn.isBlank() || ds.isBlank()) {
                    Log.i(TAG, "三元组未配置，跳过开机自启")
                    return@postDelayed
                }
                if (manualDisconnect) {
                    Log.i(TAG, "上次用户主动断开，跳过开机自启")
                    return@postDelayed
                }
                if (manager.isConnected()) {
                    Log.i(TAG, "已连接，跳过开机自启")
                    return@postDelayed
                }

                val config = AliyunIotManager.DeviceConfig(
                    productKey = pk,
                    deviceName = dn,
                    deviceSecret = ds,
                    region = prefs.getString("region", "cn-shanghai") ?: "cn-shanghai"
                )
                Log.i(TAG, "开机自启连接: deviceName=$dn")
                manager.connect(config)
            } catch (e: Exception) {
                Log.e(TAG, "开机自启异常", e)
            } finally {
                pendingResult.finish()
            }
        }, DELAY_MS)
    }
}