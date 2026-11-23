package io.github.offlineglass

import android.app.Application
import io.github.offlineglass.config.ModernSettingsBridge

class LiquidTabApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ModernSettingsBridge.initialize(this)
    }
}
