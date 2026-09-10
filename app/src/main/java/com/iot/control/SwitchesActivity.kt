package com.iot.control

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.gridlayout.widget.GridLayout
import com.iot.control.databinding.ActivitySwitchesBinding

class SwitchesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySwitchesBinding
    private lateinit var iotManager: AliyunIotManager
    private lateinit var prefs: SharedPreferences

    private val switchButtons = ArrayList<Button>()
    private val switchStates = BooleanArray(10) { false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySwitchesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("iot_config", MODE_PRIVATE)
        iotManager = (application as IoTApp).iotManager

        binding.btnBack.setOnClickListener { finish() }

        loadSwitchStates()
        setupSwitches()
        refreshEnabled()
    }

    private fun setupSwitches() {
        binding.gridSwitches.removeAllViews()
        switchButtons.clear()

        val size = dp(64) // 固定适中尺寸
        val marginPx = dp(8)

        for (i in 1..10) {
            val idx = i - 1
            val btn = Button(this).apply {
                text = "开关$i: ${if (switchStates[idx]) "开" else "关"}"
                textSize = 12f
                setTextColor(getColor(R.color.white))
                setBackgroundResource(
                    if (switchStates[idx]) R.drawable.switch_circle_green else R.drawable.switch_circle_gray
                )
                backgroundTintList = null
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    switchStates[idx] = !switchStates[idx]
                    updateSwitchUI(idx)
                    saveSwitchStates()
                    iotManager.setSwitch(getSavedConfig(), i, switchStates[idx])
                    Toast.makeText(
                        this@SwitchesActivity,
                        "开关$i ${if (switchStates[idx]) "开启" else "关闭"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = size
                height = size
                // 2 列等宽分配(weight=1)，按钮在列内居中(gravity=CENTER)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.CENTER, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.CENTER)
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
            btn.layoutParams = params
            binding.gridSwitches.addView(btn)
            switchButtons.add(btn)
        }
    }

    /** 从 SharedPreferences 读取上次开关状态 */
    private fun loadSwitchStates() {
        val saved = prefs.getString("switch_states", null) ?: return
        val parts = saved.split(",")
        for (i in 0 until minOf(parts.size, 10)) {
            switchStates[i] = parts[i] == "1"
        }
    }

    /** 保存开关状态到 SharedPreferences */
    private fun saveSwitchStates() {
        val sb = StringBuilder()
        for (i in 0 until 10) {
            if (i > 0) sb.append(",")
            sb.append(if (switchStates[i]) "1" else "0")
        }
        prefs.edit().putString("switch_states", sb.toString()).apply()
    }

    private fun updateSwitchUI(index: Int) {
        val btn = switchButtons[index]
        if (switchStates[index]) {
            btn.text = "开关${index + 1}: 开"
            btn.setBackgroundResource(R.drawable.switch_circle_green)
        } else {
            btn.text = "开关${index + 1}: 关"
            btn.setBackgroundResource(R.drawable.switch_circle_gray)
        }
    }

    private fun refreshEnabled() {
        val enabled = iotManager.isConnected()
        for (btn in switchButtons) {
            btn.isEnabled = enabled
            btn.alpha = if (enabled) 1f else 0.5f
        }
    }

    private fun getSavedConfig(): AliyunIotManager.DeviceConfig {
        return AliyunIotManager.DeviceConfig(
            productKey = prefs.getString("productKey", "") ?: "",
            deviceName = prefs.getString("deviceName", "") ?: "",
            deviceSecret = prefs.getString("deviceSecret", "") ?: "",
            region = prefs.getString("region", "cn-shanghai") ?: "cn-shanghai"
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
