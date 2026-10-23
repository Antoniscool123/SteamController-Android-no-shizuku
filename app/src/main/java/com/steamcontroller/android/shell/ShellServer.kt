package com.steamcontroller.android.shell

import android.util.Log
import com.steamcontroller.android.uinput.UInputNative
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * Entry point of the shell-UID helper process. It is started by the app itself through its
 * own wireless-ADB connection (see ShellLauncher), via `app_process`, so it runs as `shell`
 * (UID 2000) — the only unprivileged identity that may open /dev/uinput and run screencap.
 *
 * It listens on 127.0.0.1 and speaks the tiny binary protocol in [Op]. The app's first
 * (role [ROLE_CONTROL]) connection is the lifeline: when it closes, for whatever reason
 * (app killed, updated, crashed), this process tears its uinput devices down and exits.
 * That is what stops stale helpers from piling up and showing several virtual pads.
 *
 * No Android Context here: this is not an app process.
 */
object ShellServer {

    private const val TAG = "SCShellServer"

    const val ROLE_CONTROL = 0
    const val ROLE_SHELL = 1

    /** Wire opcodes, shared with the app-side UInputClient. */
    object Op {
        const val CAN_CREATE = 1
        const val CREATE_GAMEPAD = 2
        const val SEND_FRAME = 3
        const val SEND_MOUSE_FRAME = 4
        const val POLL_FF = 5
        const val RUN_SHELL = 6
        const val RUN_SHELL_OUTPUT = 7
        const val DESTROY = 8
    }

    /** Gives up if the app never connects (launch aborted), so nothing is left running. */
    private const val FIRST_CONNECT_TIMEOUT_MS = 60_000

    @Volatile private var controlConnected = false

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            Log.e(TAG, "usage: <port> <token> <nativeLibDir>")
            exitProcess(2)
        }
        val port = args[0].toInt()
        val token = args[1]
        val libDir = args[2]

        try {
            killStaleInstances()
            UInputNative.load("$libDir/libuinput_jni.so")
        } catch (t: Throwable) {
            Log.e(TAG, "startup failed: ${t.message}")
            exitProcess(3)
        }

        val server = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = FIRST_CONNECT_TIMEOUT_MS
        Log.i(TAG, "listening on 127.0.0.1:$port")

        while (true) {
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                if (!controlConnected) {
                    Log.w(TAG, "no client connected, exiting")
                    exitProcess(0)
                }
                continue
            }
            server.soTimeout = 0
            Thread({ serve(socket, token) }, "sc-conn").apply { isDaemon = true; start() }
        }
    }

    private fun serve(socket: Socket, token: String) {
        var role = -1
        try {
            socket.tcpNoDelay = true
            val din = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val dout = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            val presented = din.readUTF()
            role = din.readByte().toInt()
            val ok = MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())
            dout.writeByte(if (ok) 1 else 0)
            dout.flush()
            if (!ok) {
                socket.close()
                return
            }
            if (role == ROLE_CONTROL) controlConnected = true

            while (true) {
                when (din.readUnsignedByte()) {
                    Op.CAN_CREATE -> {
                        dout.writeBoolean(safe(false) { UInputNative.canOpen() })
                        dout.flush()
                    }
                    Op.CREATE_GAMEPAD -> {
                        val id = din.readInt()
                        dout.writeBoolean(safe(false) { UInputNative.createDevice(id) })
                        dout.flush()
                    }
                    Op.SEND_FRAME -> {
                        val v = IntArray(9) { din.readInt() }
                        safe(Unit) {
                            UInputNative.sendFrame(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8])
                        }
                    }
                    Op.SEND_MOUSE_FRAME -> {
                        val a = din.readInt(); val b = din.readInt()
                        val c = din.readInt(); val d = din.readInt()
                        safe(Unit) { UInputNative.sendMouseFrame(a, b, c, d) }
                    }
                    Op.POLL_FF -> {
                        val ff = safe<IntArray?>(null) { UInputNative.pollFFEvent() }
                        if (ff != null && ff.size == 2) {
                            dout.writeBoolean(true)
                            dout.writeInt(ff[0]); dout.writeInt(ff[1])
                        } else {
                            dout.writeBoolean(false)
                        }
                        dout.flush()
                    }
                    Op.RUN_SHELL -> {
                        val cmd = readCmd(din)
                        dout.writeInt(runShell(cmd))
                        dout.flush()
                    }
                    Op.RUN_SHELL_OUTPUT -> {
                        val cmd = readCmd(din)
                        val out = runShellForOutput(cmd)
                        dout.writeBoolean(out != null)
                        if (out != null) dout.writeUTF(out.take(20_000))
                        dout.flush()
                    }
                    Op.DESTROY -> {
                        shutdown()
                        return
                    }
                    else -> throw IllegalStateException("bad opcode")
                }
            }
        } catch (t: Throwable) {
            // EOF / reset is the normal way a connection ends.
            if (role == ROLE_CONTROL) {
                Log.i(TAG, "control connection closed (${t.javaClass.simpleName}), shutting down")
                shutdown()
            }
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun shutdown() {
        // The kernel drops a uinput device when its fd closes, so exiting is the cleanup.
        safe(Unit) { UInputNative.destroy() }
        exitProcess(0)
    }

    private fun readCmd(din: DataInputStream): List<String> {
        val n = din.readInt()
        require(n in 1..64) { "bad argc" }
        return List(n) { din.readUTF() }
    }

    private fun runShell(cmd: List<String>): Int = try {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        proc.inputStream.readBytes() // drain so the child cannot block on a full pipe
        val exit = proc.waitFor()
        Log.i(TAG, "shell ${cmd.joinToString(" ")} -> $exit")
        exit
    } catch (t: Throwable) {
        Log.e(TAG, "shell failed: ${t.message}")
        -1
    }

    private fun runShellForOutput(cmd: List<String>): String? = try {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        text.trim()
    } catch (t: Throwable) {
        Log.e(TAG, "shell failed: ${t.message}")
        null
    }

    private inline fun <T> safe(fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        Log.e(TAG, "native call failed: ${t.message}")
        fallback
    }

    /**
     * Kills every other process with this same name. Anything left from an earlier launch
     * would still hold uinput devices (games would then see several pads). All instances
     * run as shell, so we may kill each other.
     */
    private fun killStaleInstances() {
        val myPid = android.os.Process.myPid()
        val myName = cmdline(myPid) ?: return
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid != myPid && cmdline(pid) == myName) {
                Log.w(TAG, "killing stale $myName (pid $pid)")
                android.os.Process.killProcess(pid)
            }
        }
    }

    private fun cmdline(pid: Int): String? = try {
        File("/proc/$pid/cmdline").readText().substringBefore('\u0000').takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}
