package com.iot.control

import android.app.Application

class IoTApp : Application() {
    val iotManager: AliyunIotManager by lazy { AliyunIotManager(this) }
}
