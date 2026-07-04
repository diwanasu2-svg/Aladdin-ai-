package com.aladdin.plugin.manager

import android.util.Log
import com.aladdin.plugin.api.PluginLogger

class AndroidPluginLogger(private val pluginId: String) : PluginLogger {
    override fun d(tag: String, msg: String) { Log.d("Plugin[$pluginId]/$tag", msg) }
    override fun i(tag: String, msg: String) { Log.i("Plugin[$pluginId]/$tag", msg) }
    override fun w(tag: String, msg: String) { Log.w("Plugin[$pluginId]/$tag", msg) }
    override fun e(tag: String, msg: String, throwable: Throwable?) {
        Log.e("Plugin[$pluginId]/$tag", msg, throwable)
    }
}
