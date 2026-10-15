package com.wireless.audiostreamer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wireless.audiostreamer.R
import com.wireless.audiostreamer.audio.JitterBuffer
import com.wireless.audiostreamer.audio.LowLatencyPlayer
import com.wireless.audiostreamer.audio.MicCapturer
import com.wireless.audiostreamer.camera.CameraCapturer
import com.wireless.audiostreamer.network.ControlChannel
import com.wireless.audiostreamer.network.CtrlProtocol
import com.wireless.audiostreamer.network.FsBrowser
import com.wireless.audiostreamer.network.PacketCipher
import com.wireless.audiostreamer.network.Protocol
import com.wireless.audiostreamer.ui.IncomingRequestActivity
import com.wireless.audiostreamer.ui.MainActivity
import com.wireless.audiostreamer.util.AppPrefs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Handles remote mic / camera / storage access with an explicit, per-request Accept/Decline
 * step on the phone being accessed (see IncomingRequestActivity) and a persistent, Stop-able
 * notification for as long as anything is actually being shared. Nothing here can start
 * without that consent step completing first.
 *
 * This service listens passively (plain background Service, no notification) for inbound
 * requests whenever the app is open, and only promotes itself to a foreground service —
 * with the ongoing notification — once a capability is actually active.
 */
class RemoteAccessService : Service() {

    interface EventListener {
        fun onIncomingRequest(peerName: String, capability: Int) {}
        fun onAccepted(capability: Int) {}
        fun onDeclined(capability: Int) {}
        fun onStopped(capability: Int) {}
        fun onCameraFrame(jpeg: ByteArray) {}
        fun onFsList(entries: List<FsBrowser.Entry>?) {}
        fun onFsProgress(name: String, receivedBytes: Long, totalBytes: Long) {}
        fun onFsDone(name: String) {}
        fun onError(message: String) {}
    }

    companion object {
        const val ACTION_REQUEST = "com.wireless.audiostreamer.ra.REQUEST"
        const val ACTION_RESPOND = "com.wireless.audiostreamer.ra.RESPOND"
        const val ACTION_STOP_CAP = "com.wireless.audiostreamer.ra.STOP_CAP"
        const val ACTION_FS_LIST = "com.wireless.audiostreamer.ra.FS_LIST"
        const val ACTION_FS_GET = "com.wireless.audiostreamer.ra.FS_GET"
        const val EXTRA_PEER_IP = "peer_ip"
        const val EXTRA_PEER_NAME = "peer_name"
        const val EXTRA_CAP = "cap"
        const val EXTRA_ACCEPT = "accept"
        const val EXTRA_PATH = "path"

        private const val CHANNEL_ID = "remote_access_channel"
        private const val REQUEST_CHANNEL_ID = "remote_request_channel"
        private const val NOTIF_ID = 2
        private const val REQUEST_NOTIF_ID = 3

        @Volatile var listener: EventListener? = null
        @Volatile var activeCapabilities: MutableSet<Int> = HashSet()
        @Volatile var connectedPeerName: String = ""
    }

    private val main = Handler(Looper.getMainLooper())
    private var channel: ControlChannel? = null

    private var mic: MicCapturer? = null
    private var cam: CameraCapturer? = null
    private var jitter: JitterBuffer? = null
    private var player: LowLatencyPlayer? = null
    private var micRxSeq = 0

    private var curPath = ""
    private var pendingFsOut: FileOutputStream? = null
    private var pendingFsName = ""
    private var pendingFsTotal = 0L
    private var pendingFsReceived = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        listenPassively()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REQUEST -> {
                val ip = intent.getStringExtra(EXTRA_PEER_IP) ?: return START_STICKY
                val cap = intent.getIntExtra(EXTRA_CAP, 0)
                startOutgoing(ip, cap)
            }
            ACTION_RESPOND -> {
                val accept = intent.getBooleanExtra(EXTRA_ACCEPT, false)
                val cap = intent.getIntExtra(EXTRA_CAP, 0)
                respond(accept, cap)
            }
            ACTION_STOP_CAP -> {
                val cap = intent.getIntExtra(EXTRA_CAP, 0)
                stopCapability(cap, notifyPeer = true)
            }
            ACTION_FS_LIST -> {
                curPath = intent.getStringExtra(EXTRA_PATH) ?: ""
                channel?.send(CtrlProtocol.M_FS_LIST_REQ, curPath.toByteArray(Charsets.UTF_8))
            }
            ACTION_FS_GET -> {
                val path = intent.getStringExtra(EXTRA_PATH) ?: ""
                channel?.send(CtrlProtocol.M_FS_GET_REQ, path.toByteArray(Charsets.UTF_8))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAllCapabilities()
        channel?.stop()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ connection

    private fun listenPassively() {
        if (!AppPrefs.isConfigured(this)) return
        val ch = ControlChannel(PacketCipher(AppPrefs.pin(this)))
        channel = ch
        ch.startServer(serverListener)
    }

    private fun startOutgoing(ip: String, cap: Int) {
        channel?.stop()
        val ch = ControlChannel(PacketCipher(AppPrefs.pin(this)))
        channel = ch
        ch.startClient(ip, clientListener(cap))
    }

    private val serverListener = object : ControlChannel.Listener {
        override fun onState(connected: Boolean) {
            if (!connected) main.post { listenPassively() } // ready for the next request
        }

        override fun onMessage(subtype: Int, payload: ByteArray) = handleIncoming(subtype, payload)
    }

    private fun clientListener(firstCap: Int) = object : ControlChannel.Listener {
        override fun onState(connected: Boolean) {
            if (connected) {
                channel?.send(
                    CtrlProtocol.M_REQUEST,
                    byteArrayOf(firstCap.toByte()) + AppPrefs.deviceName().toByteArray(Charsets.UTF_8)
                )
            } else {
                main.post { listener?.onError(getString(R.string.ra_connect_failed)) }
            }
        }

        override fun onMessage(subtype: Int, payload: ByteArray) = handleIncoming(subtype, payload)
    }

    // ------------------------------------------------------------------ message handling

    private fun handleIncoming(subtype: Int, payload: ByteArray) {
        when (subtype) {
            CtrlProtocol.M_REQUEST -> {
                val cap = payload.getOrNull(0)?.toInt() ?: return
                val name = String(payload, 1, payload.size - 1, Charsets.UTF_8)
                connectedPeerName = name
                main.post {
                    listener?.onIncomingRequest(name, cap)
                    showIncomingNotification(name, cap)
                }
            }
            CtrlProtocol.M_ACCEPT -> {
                val cap = payload.getOrNull(0)?.toInt() ?: return
                if (cap == CtrlProtocol.CAP_MIC) startMicPlayback()
                main.post { listener?.onAccepted(cap) }
            }
            CtrlProtocol.M_DECLINE -> {
                val cap = payload.getOrNull(0)?.toInt() ?: return
                main.post { listener?.onDeclined(cap) }
            }
            CtrlProtocol.M_STOP -> {
                val cap = payload.getOrNull(0)?.toInt() ?: return
                stopCapability(cap, notifyPeer = false)
                main.post { listener?.onStopped(cap) }
            }
            CtrlProtocol.M_MIC_FRAME -> {
                val audio = Protocol.parseAudioPlain(payload) ?: return
                if (jitter == null) startMicPlayback()
                jitter?.put(micRxSeq++, audio.pcm)
            }
            CtrlProtocol.M_CAM_FRAME -> main.post { listener?.onCameraFrame(payload) }
            CtrlProtocol.M_FS_LIST_REQ -> {
                val path = String(payload, Charsets.UTF_8)
                if (!storageAllowed()) {
                    channel?.send(CtrlProtocol.M_FS_ERROR, getString(R.string.ra_storage_permission_needed).toByteArray(Charsets.UTF_8))
                } else {
                    val entries = FsBrowser.list(path)
                    channel?.send(CtrlProtocol.M_FS_LIST_RESP, FsBrowser.encodeList(entries ?: emptyList()))
                }
            }
            CtrlProtocol.M_FS_LIST_RESP -> {
                val entries = FsBrowser.decodeList(payload)
                main.post { listener?.onFsList(entries) }
            }
            CtrlProtocol.M_FS_GET_REQ -> serveFile(String(payload, Charsets.UTF_8))
            CtrlProtocol.M_FS_META -> {
                val size = ByteBuffer.wrap(payload, 0, 8).long
                val name = String(payload, 8, payload.size - 8, Charsets.UTF_8)
                beginReceivingFile(name, size)
            }
            CtrlProtocol.M_FS_CHUNK -> receiveFileChunk(payload)
            CtrlProtocol.M_FS_DONE -> finishReceivingFile()
            CtrlProtocol.M_FS_ERROR -> main.post { listener?.onError(String(payload, Charsets.UTF_8)) }
        }
    }

    // ------------------------------------------------------------------ consent flow

    /** Called only from IncomingRequestActivity, only after the person taps Accept or Decline. */
    fun respond(accept: Boolean, cap: Int) {
        if (!accept) {
            channel?.send(CtrlProtocol.M_DECLINE, byteArrayOf(cap.toByte()))
            cancelIncomingNotification()
            return
        }
        channel?.send(CtrlProtocol.M_ACCEPT, byteArrayOf(cap.toByte()))
        cancelIncomingNotification()
        startCapability(cap)
    }

    private fun startCapability(cap: Int) {
        when (cap) {
            CtrlProtocol.CAP_MIC -> {
                val m = MicCapturer { pcm ->
                    channel?.send(CtrlProtocol.M_MIC_FRAME, Protocol.buildAudioPlain(pcm, 1))
                }
                if (m.start()) {
                    mic = m
                    activeCapabilities.add(cap)
                } else {
                    main.post { listener?.onError(getString(R.string.ra_mic_failed)) }
                    return
                }
            }
            CtrlProtocol.CAP_CAM_FRONT, CtrlProtocol.CAP_CAM_BACK -> {
                val facing = if (cap == CtrlProtocol.CAP_CAM_FRONT)
                    CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                val c = CameraCapturer(this, facing) { jpeg -> channel?.send(CtrlProtocol.M_CAM_FRAME, jpeg) }
                if (c.start()) {
                    cam = c
                    activeCapabilities.add(cap)
                } else {
                    main.post { listener?.onError(getString(R.string.ra_camera_failed)) }
                    return
                }
            }
            CtrlProtocol.CAP_STORAGE -> activeCapabilities.add(cap)
        }
        promoteForeground()
    }

    private fun stopCapability(cap: Int, notifyPeer: Boolean) {
        when (cap) {
            CtrlProtocol.CAP_MIC -> {
                mic?.stop(); mic = null
                player?.stop(); player = null; jitter = null; micRxSeq = 0
            }
            CtrlProtocol.CAP_CAM_FRONT, CtrlProtocol.CAP_CAM_BACK -> { cam?.stop(); cam = null }
        }
        activeCapabilities.remove(cap)
        if (notifyPeer) channel?.send(CtrlProtocol.M_STOP, byteArrayOf(cap.toByte()))
        if (activeCapabilities.isEmpty()) demoteForeground()
    }

    private fun stopAllCapabilities() {
        for (c in activeCapabilities.toList()) stopCapability(c, notifyPeer = true)
    }

    private fun startMicPlayback() {
        val jb = JitterBuffer(2)
        jitter = jb
        val p = LowLatencyPlayer(jb)
        p.start(1)
        player = p
    }

    // ------------------------------------------------------------------ storage transfer

    private fun storageAllowed(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun serveFile(path: String) {
        if (!storageAllowed()) {
            channel?.send(CtrlProtocol.M_FS_ERROR, getString(R.string.ra_storage_permission_needed).toByteArray(Charsets.UTF_8))
            return
        }
        val f = FsBrowser.resolve(path)
        if (f == null || !f.isFile) {
            channel?.send(CtrlProtocol.M_FS_ERROR, getString(R.string.ra_file_missing).toByteArray(Charsets.UTF_8))
            return
        }
        Thread {
            try {
                val nameBytes = f.name.toByteArray(Charsets.UTF_8)
                val meta = ByteBuffer.allocate(8 + nameBytes.size).putLong(f.length()).put(nameBytes).array()
                channel?.send(CtrlProtocol.M_FS_META, meta)
                FileInputStream(f).use { input ->
                    val buf = ByteArray(48 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        channel?.send(CtrlProtocol.M_FS_CHUNK, buf.copyOf(n))
                    }
                }
                channel?.send(CtrlProtocol.M_FS_DONE, ByteArray(0))
            } catch (e: Exception) {
                channel?.send(CtrlProtocol.M_FS_ERROR, getString(R.string.ra_transfer_failed).toByteArray(Charsets.UTF_8))
            }
        }.start()
    }

    private fun beginReceivingFile(name: String, size: Long) {
        try {
            val dir = File(getExternalFilesDir(null), "received")
            dir.mkdirs()
            pendingFsOut = FileOutputStream(File(dir, name))
            pendingFsName = name
            pendingFsTotal = size
            pendingFsReceived = 0
        } catch (e: Exception) {
            main.post { listener?.onError(getString(R.string.ra_transfer_failed)) }
        }
    }

    private fun receiveFileChunk(bytes: ByteArray) {
        try {
            pendingFsOut?.write(bytes)
            pendingFsReceived += bytes.size
            val name = pendingFsName
            val got = pendingFsReceived
            val total = pendingFsTotal
            main.post { listener?.onFsProgress(name, got, total) }
        } catch (e: Exception) {
        }
    }

    private fun finishReceivingFile() {
        try { pendingFsOut?.close() } catch (e: Exception) { }
        pendingFsOut = null
        val name = pendingFsName
        main.post { listener?.onFsDone(name) }
    }

    // ------------------------------------------------------------------ notifications

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.ra_notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(REQUEST_CHANNEL_ID, getString(R.string.ra_request_channel), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun showIncomingNotification(peerName: String, cap: Int) {
        val full = Intent(this, IncomingRequestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_PEER_NAME, peerName)
            .putExtra(EXTRA_CAP, cap)
        val pi = PendingIntent.getActivity(
            this, 10, full, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, REQUEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_audio)
            .setContentTitle(getString(R.string.ra_request_title, peerName))
            .setContentText(getString(capLabelRes(cap)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(REQUEST_NOTIF_ID, n)
        try { startActivity(full) } catch (e: Exception) { }
    }

    private fun cancelIncomingNotification() {
        try { getSystemService(NotificationManager::class.java).cancel(REQUEST_NOTIF_ID) } catch (e: Exception) { }
    }

    private fun promoteForeground() {
        val text = activeCapabilities.joinToString(", ") { getString(capLabelRes(it)) }
        val stopCap = activeCapabilities.firstOrNull() ?: 0
        val stop = PendingIntent.getService(
            this, 11,
            Intent(this, RemoteAccessService::class.java).setAction(ACTION_STOP_CAP).putExtra(EXTRA_CAP, stopCap),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 12, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_audio)
            .setContentTitle(getString(R.string.ra_sharing_title, connectedPeerName))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
        var type = 0
        for (c in activeCapabilities) {
            type = type or when (c) {
                CtrlProtocol.CAP_MIC -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                CtrlProtocol.CAP_CAM_FRONT, CtrlProtocol.CAP_CAM_BACK -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
        }
        if (type == 0) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } catch (e: Exception) {
            main.post { listener?.onError(getString(R.string.ra_permission_needed)) }
        }
    }

    private fun demoteForeground() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
    }

    private fun capLabelRes(cap: Int) = when (cap) {
        CtrlProtocol.CAP_MIC -> R.string.cap_mic
        CtrlProtocol.CAP_CAM_FRONT -> R.string.cap_cam_front
        CtrlProtocol.CAP_CAM_BACK -> R.string.cap_cam_back
        else -> R.string.cap_storage
    }
}
