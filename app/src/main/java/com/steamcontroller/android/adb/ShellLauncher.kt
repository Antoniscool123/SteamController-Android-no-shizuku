package com.steamcontroller.android.adb

import android.content.Context
import android.os.Build
import android.util.Log
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.shell.ShellServer
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * Starts [ShellServer] as the shell user through the app's own ADB connection and returns
 * how to reach it. This is the whole replacement for Shizuku's "start the service".
 */
object ShellLauncher {

    private const val TAG = "ShellLauncher"
    private const val LEGACY_ADB_PORT = 5555

    /** Wireless debugging is off, not paired, or (pre-Android 11) `adb tcpip` was not run. */
    class SetupNeeded(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Launch(val port: Int, val token: String)

    /** Blocking. Call from a background thread. */
    fun start(context: Context): Launch {
        val adb = AdbManager.get(context)
        ensureConnected(context, adb)

        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val token = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }

        val info = context.applicationInfo
        // setsid: the helper must outlive this adb shell session, which is torn down on close.
        val command = "CLASSPATH=${info.sourceDir} setsid app_process /system/bin " +
            "--nice-name=sc_uinput ${ShellServer::class.java.name} " +
            "$port $token ${info.nativeLibraryDir} </dev/null >/dev/null 2>&1 &"

        val stream = adb.openStream("shell:$command")
        try {
            // Read to EOF so the shell is not closed before it has forked the helper.
            val reader = Thread {
                try { stream.openInputStream().readBytes() } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }
            reader.join(8_000)
        } finally {
            try { stream.close() } catch (_: Throwable) {}
        }
        Log.i(TAG, "helper launch issued on port $port")
        return Launch(port, token)
    }

    private fun ensureConnected(context: Context, adb: AdbManager) {
        if (adb.isConnected) return
        val connected = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                adb.connectTls(context, 10_000)
            } else {
                adb.connect("127.0.0.1", LEGACY_ADB_PORT)
            }
        } catch (t: Throwable) {
            throw SetupNeeded(t.message ?: "ADB connection failed", t)
        }
        if (!connected) throw SetupNeeded("ADB connection failed")
        Prefs.setAdbReady(context, true)
    }

    /** One-time pairing with the code shown on the Wireless debugging screen (Android 11+). */
    fun pair(context: Context, port: Int, code: String) {
        AdbManager.get(context).pair("127.0.0.1", port, code)
        Prefs.setAdbReady(context, true)
    }
}
