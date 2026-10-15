package com.wireless.audiostreamer.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Reliable, encrypted control channel over TCP. Used for access requests, consent,
 * live mic/camera frames and file transfer. Every message is sealed with AES-GCM
 * (same key scheme as the audio channel, derived from the shared PIN) and framed with
 * a 4-byte length so it survives TCP's stream semantics.
 */
class ControlChannel(private val cipher: PacketCipher) {
    interface Listener {
        fun onState(connected: Boolean)
        fun onMessage(subtype: Int, payload: ByteArray)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private val session = SecureRandom().nextLong()
    private var seq = 0
    private val writeLock = ReentrantLock()
    private var thread: Thread? = null

    fun startServer(listener: Listener) {
        stop()
        running = true
        thread = thread(isDaemon = true, name = "was-ctrl-srv") {
            while (running) {
                var srv: ServerSocket? = null
                try {
                    val created = ServerSocket()
                    created.reuseAddress = true
                    created.bind(InetSocketAddress(CtrlProtocol.PORT))
                    srv = created
                    server = created
                    val s = created.accept()
                    handle(s, listener)
                } catch (e: Exception) {
                    if (running) sleepQuiet(1000)
                } finally {
                    try { srv?.close() } catch (e: Exception) { }
                    server = null
                }
            }
        }
    }

    fun startClient(ip: String, listener: Listener) {
        stop()
        running = true
        thread = thread(isDaemon = true, name = "was-ctrl-cli") {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, CtrlProtocol.PORT), 5000)
                handle(s, listener)
            } catch (e: Exception) {
                listener.onState(false)
            }
        }
    }

    fun send(subtype: Int, payload: ByteArray): Boolean {
        val s = socket ?: return false
        val plain = ByteArray(1 + payload.size)
        plain[0] = subtype.toByte()
        System.arraycopy(payload, 0, plain, 1, payload.size)
        val frame = cipher.seal(CtrlProtocol.T_CTRL, session, seq++, plain)
        return writeLock.withLock {
            try {
                val o = DataOutputStream(s.getOutputStream())
                o.writeInt(frame.size)
                o.write(frame)
                o.flush()
                true
            } catch (e: IOException) {
                false
            }
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (e: Exception) { }
        try { server?.close() } catch (e: Exception) { }
        socket = null
        server = null
        thread?.interrupt()
    }

    private fun handle(s: Socket, listener: Listener) {
        socket = s
        listener.onState(true)
        try {
            val din = DataInputStream(s.getInputStream())
            while (running) {
                val len = din.readInt()
                if (len <= 0 || len > MAX_FRAME) break
                val buf = ByteArray(len)
                din.readFully(buf)
                val o = cipher.open(buf, buf.size) ?: continue
                if (o.type != CtrlProtocol.T_CTRL || o.plain.isEmpty()) continue
                listener.onMessage(o.plain[0].toInt() and 0xFF, o.plain.copyOfRange(1, o.plain.size))
            }
        } catch (e: Exception) {
        } finally {
            try { s.close() } catch (e: Exception) { }
            socket = null
            listener.onState(false)
        }
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { }
    }

    private companion object {
        const val MAX_FRAME = 2_000_000 // generous: allows large JPEG/file chunks
    }
}
