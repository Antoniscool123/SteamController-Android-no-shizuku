package com.steamcontroller.android.shell

import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * App-side handle on the shell-UID [ShellServer]. Same method names the old AIDL
 * interface had, so the gamepad code barely changes.
 *
 * Two connections: the control one carries frames and rumble polling (and is the server's
 * lifeline); a second one runs shell commands, so a slow `screencap` never stalls input.
 * Every call may throw IOException once the server is gone.
 */
class UInputClient(private val port: Int, private val token: String) {

    private class Conn(val socket: Socket, val din: DataInputStream, val dout: DataOutputStream)

    private val controlLock = Any()
    private val shellLock = Any()
    @Volatile private var control: Conn? = null
    @Volatile private var shell: Conn? = null

    /** Retries until the freshly started server is listening. */
    fun connect(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                control = open(ShellServer.ROLE_CONTROL)
                shell = open(ShellServer.ROLE_SHELL)
                return true
            } catch (_: IOException) {
                close()
                try { Thread.sleep(250) } catch (_: InterruptedException) { return false }
            }
        }
        return false
    }

    private fun open(role: Int): Conn {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
            val din = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val dout = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            dout.writeUTF(token)
            dout.writeByte(role)
            dout.flush()
            if (din.readByte().toInt() != 1) throw IOException("server rejected token")
            return Conn(socket, din, dout)
        } catch (t: Throwable) {
            try { socket.close() } catch (_: Throwable) {}
            throw if (t is IOException) t else IOException(t)
        }
    }

    private fun ctl(): Conn = control ?: throw IOException("not connected")
    private fun sh(): Conn = shell ?: throw IOException("not connected")

    fun canCreateDevice(): Boolean = synchronized(controlLock) {
        val c = ctl()
        c.dout.writeByte(ShellServer.Op.CAN_CREATE); c.dout.flush()
        c.din.readBoolean()
    }

    fun createGamepad(profileId: Int): Boolean = synchronized(controlLock) {
        val c = ctl()
        c.dout.writeByte(ShellServer.Op.CREATE_GAMEPAD)
        c.dout.writeInt(profileId); c.dout.flush()
        c.din.readBoolean()
    }

    fun sendFrame(
        buttons: Int,
        leftStickX: Int, leftStickY: Int,
        rightStickX: Int, rightStickY: Int,
        leftTrigger: Int, rightTrigger: Int,
        dpadX: Int, dpadY: Int,
    ) = synchronized(controlLock) {
        val c = ctl()
        c.dout.writeByte(ShellServer.Op.SEND_FRAME)
        for (v in intArrayOf(buttons, leftStickX, leftStickY, rightStickX, rightStickY,
            leftTrigger, rightTrigger, dpadX, dpadY)) c.dout.writeInt(v)
        c.dout.flush()
    }

    fun sendMouseFrame(relX: Int, relY: Int, scrollY: Int, keys: Int) = synchronized(controlLock) {
        val c = ctl()
        c.dout.writeByte(ShellServer.Op.SEND_MOUSE_FRAME)
        c.dout.writeInt(relX); c.dout.writeInt(relY); c.dout.writeInt(scrollY); c.dout.writeInt(keys)
        c.dout.flush()
    }

    /** [strong, weak] in 0..65535, or null if no rumble arrived since the last poll. */
    fun pollForceFeedback(): IntArray? = synchronized(controlLock) {
        val c = ctl()
        c.dout.writeByte(ShellServer.Op.POLL_FF); c.dout.flush()
        if (c.din.readBoolean()) intArrayOf(c.din.readInt(), c.din.readInt()) else null
    }

    fun runShellCommand(cmd: Array<String>): Int = synchronized(shellLock) {
        val c = sh()
        c.dout.writeByte(ShellServer.Op.RUN_SHELL)
        writeCmd(c, cmd)
        c.din.readInt()
    }

    fun runShellCommandForOutput(cmd: Array<String>): String? = synchronized(shellLock) {
        val c = sh()
        c.dout.writeByte(ShellServer.Op.RUN_SHELL_OUTPUT)
        writeCmd(c, cmd)
        if (c.din.readBoolean()) c.din.readUTF() else null
    }

    private fun writeCmd(c: Conn, cmd: Array<String>) {
        c.dout.writeInt(cmd.size)
        cmd.forEach { c.dout.writeUTF(it) }
        c.dout.flush()
    }

    /** Asks the server to remove its devices and exit. */
    fun destroy() {
        try {
            synchronized(controlLock) {
                val c = control ?: return
                c.dout.writeByte(ShellServer.Op.DESTROY); c.dout.flush()
            }
        } catch (_: Throwable) {
        } finally {
            close()
        }
    }

    fun close() {
        try { control?.socket?.close() } catch (_: Throwable) {}
        try { shell?.socket?.close() } catch (_: Throwable) {}
        control = null
        shell = null
    }
}
