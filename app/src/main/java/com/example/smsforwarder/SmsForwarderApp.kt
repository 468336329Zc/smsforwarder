package com.example.smsforwarder

import android.app.Application
import com.example.smsforwarder.util.AppLogger

class SmsForwarderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
    }
}
