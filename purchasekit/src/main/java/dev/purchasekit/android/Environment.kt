package dev.purchasekit.android

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Environment {
    @SerialName("sandbox") Sandbox,
    @SerialName("production") Production;

    companion object {
        fun current(context: Context): Environment {
            return if (isDebuggable(context)) Sandbox else Production
        }

        private fun isDebuggable(context: Context): Boolean {
            return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }
    }
}
