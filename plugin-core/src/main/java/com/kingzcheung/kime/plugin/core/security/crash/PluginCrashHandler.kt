package com.kingzcheung.kime.plugin.core.security.crash

import android.app.Application
import android.content.Intent
import android.content.res.Resources
import android.os.Process
import android.util.Log
import com.kingzcheung.kime.plugin.core.exception.PluginDependencyException
import com.kingzcheung.kime.plugin.core.model.PluginCrashInfo
import com.kingzcheung.kime.plugin.core.runtime.PluginManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object PluginCrashHandler : Thread.UncaughtExceptionHandler {

    const val EXTRA_CRASH_INFO = "CRASH_INFO"
    private const val TAG = "PluginCrashHandler"

    private lateinit var context: Application
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var globalCallback: IPluginCrashCallback? = null
    private val pluginCallbacks = ConcurrentHashMap<String, IPluginCrashCallback>()

    fun initialize(context: Application) {
        if (this::context.isInitialized) {
            Log.w(TAG, "PluginCrashHandler already initialized")
            return
        }
        this.context = context
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "Plugin crash handler registered")
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val wasHandled = handlePluginRelatedException(throwable)
            if (!wasHandled) {
                Log.d(TAG, "Exception not plugin-related, delegating to default handler")
                defaultHandler?.uncaughtException(thread, throwable)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in PluginCrashHandler", e)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun handlePluginRelatedException(throwable: Throwable): Boolean {
        val culpritPluginId = findCulpritPluginId(throwable)
        val pluginCallback = culpritPluginId?.let { pluginCallbacks[it] }

        val crashInfo = PluginCrashInfo(
            throwable = throwable,
            culpritPluginId = culpritPluginId,
            defaultMessage = buildDefaultMessage(throwable, culpritPluginId)
        )

        findCause<PluginDependencyException>(throwable)?.let {
            val depCrashInfo = PluginCrashInfo(it, it.culpritPluginId, "Plugin dependency missing: ${it.missingClassName}")
            if (pluginCallback?.onDependencyException(depCrashInfo) == true) return true
            if (globalCallback?.onDependencyException(depCrashInfo) == true) return true
            showCrashActivity(depCrashInfo)
            return true
        }

        findCause<ClassCastException>(throwable)?.let {
            if (pluginCallback?.onClassCastException(crashInfo) == true) return true
            if (globalCallback?.onClassCastException(crashInfo) == true) return true
            showCrashActivity(crashInfo)
            return true
        }

        findCause<Resources.NotFoundException>(throwable)?.let {
            if (pluginCallback?.onResourceNotFoundException(crashInfo) == true) return true
            if (globalCallback?.onResourceNotFoundException(crashInfo) == true) return true
            showCrashActivity(crashInfo)
            return true
        }

        if (throwable is NoSuchMethodError || throwable is NoSuchFieldError || throwable is AbstractMethodError) {
            if (culpritPluginId != null) {
                val apiCrashInfo = PluginCrashInfo(throwable, culpritPluginId, "Plugin version incompatible with app")
                if (pluginCallback?.onApiIncompatibleException(apiCrashInfo) == true) return true
                if (globalCallback?.onApiIncompatibleException(apiCrashInfo) == true) return true
                showCrashActivity(apiCrashInfo)
                return true
            }
        }

        if (culpritPluginId != null) {
            if (pluginCallback?.onOtherPluginException(crashInfo) == true) return true
            if (globalCallback?.onOtherPluginException(crashInfo) == true) return true
            showCrashActivity(crashInfo)
            return true
        }

        return false
    }

    private fun findCulpritPluginId(throwable: Throwable?): String? {
        var current: Throwable? = throwable
        while (current != null) {
            for (element in current.stackTrace) {
                val pluginId = PluginManager.getClassIndex()[element.className]
                if (pluginId != null) {
                    return pluginId
                }
            }
            current = current.cause
        }
        return null
    }

    private inline fun <reified T : Throwable> findCause(throwable: Throwable): T? {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    private fun buildDefaultMessage(throwable: Throwable, pluginId: String?): String {
        return if (pluginId != null) {
            "Plugin '$pluginId' crashed: ${throwable.message}"
        } else {
            "Unknown error: ${throwable.message}"
        }
    }

    private fun showCrashActivity(crashInfo: PluginCrashInfo) {
        try {
            val intent = Intent(context, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_CRASH_INFO, crashInfo)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch CrashActivity", e)
        }
        killProcess()
    }

    private fun killProcess() {
        android.os.Handler(context.mainLooper).postDelayed({
            Process.killProcess(Process.myPid())
        }, 500)
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun setGlobalCrashCallback(callback: IPluginCrashCallback?) {
        GlobalScope.launch {
            globalCallback = callback
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun setCrashCallback(pluginId: String, callback: IPluginCrashCallback?) {
        GlobalScope.launch {
            if (callback == null) {
                pluginCallbacks.remove(pluginId)
            } else {
                pluginCallbacks[pluginId] = callback
            }
        }
    }

    fun clearCallbacks() {
        globalCallback = null
        pluginCallbacks.clear()
    }
}