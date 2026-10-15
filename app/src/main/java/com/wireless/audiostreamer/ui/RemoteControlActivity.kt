package com.wireless.audiostreamer.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.wireless.audiostreamer.R
import com.wireless.audiostreamer.network.CtrlProtocol
import com.wireless.audiostreamer.network.FsBrowser
import com.wireless.audiostreamer.service.RemoteAccessService

/** Requester side: pick mic / front camera / back camera / storage on a chosen peer. */
class RemoteControlActivity : BaseActivity(), RemoteAccessService.EventListener {

    private lateinit var tvStatus: TextView
    private lateinit var tvPath: TextView
    private lateinit var ivCamera: ImageView
    private lateinit var fileList: LinearLayout
    private var peerIp = ""
    private var peerName = ""
    private var activeCap = 0
    private var curPath = ""
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_control)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        peerIp = intent.getStringExtra(RemoteAccessService.EXTRA_PEER_IP) ?: ""
        peerName = intent.getStringExtra(RemoteAccessService.EXTRA_PEER_NAME) ?: peerIp
        title = peerName

        tvStatus = findViewById(R.id.tvRcStatus)
        tvPath = findViewById(R.id.tvPath)
        ivCamera = findViewById(R.id.ivCamera)
        fileList = findViewById(R.id.fileList)

        findViewById<Button>(R.id.btnReqMic).setOnClickListener { request(CtrlProtocol.CAP_MIC) }
        findViewById<Button>(R.id.btnReqCamFront).setOnClickListener { request(CtrlProtocol.CAP_CAM_FRONT) }
        findViewById<Button>(R.id.btnReqCamBack).setOnClickListener { request(CtrlProtocol.CAP_CAM_BACK) }
        findViewById<Button>(R.id.btnReqStorage).setOnClickListener { request(CtrlProtocol.CAP_STORAGE) }
        findViewById<Button>(R.id.btnStopCap).setOnClickListener { stopActive() }
    }

    override fun onStart() {
        super.onStart()
        RemoteAccessService.listener = this
    }

    override fun onStop() {
        RemoteAccessService.listener = null
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun request(cap: Int) {
        activeCap = cap
        tvStatus.text = getString(R.string.ra_waiting)
        startService(
            Intent(this, RemoteAccessService::class.java)
                .setAction(RemoteAccessService.ACTION_REQUEST)
                .putExtra(RemoteAccessService.EXTRA_PEER_IP, peerIp)
                .putExtra(RemoteAccessService.EXTRA_CAP, cap)
        )
    }

    private fun stopActive() {
        if (activeCap == 0) return
        startService(
            Intent(this, RemoteAccessService::class.java)
                .setAction(RemoteAccessService.ACTION_STOP_CAP)
                .putExtra(RemoteAccessService.EXTRA_CAP, activeCap)
        )
        activeCap = 0
        ivCamera.setImageDrawable(null)
        fileList.removeAllViews()
        tvPath.text = ""
        tvStatus.setText(R.string.status_idle)
    }

    override fun onAccepted(capability: Int) {
        ui.post {
            tvStatus.text = getString(R.string.ra_sharing_title, peerName)
            if (capability == CtrlProtocol.CAP_STORAGE) {
                curPath = ""
                refreshList()
            }
        }
    }

    override fun onDeclined(capability: Int) {
        ui.post { tvStatus.setText(R.string.ra_declined) }
    }

    override fun onStopped(capability: Int) {
        ui.post {
            tvStatus.setText(R.string.status_idle)
            ivCamera.setImageDrawable(null)
        }
    }

    override fun onCameraFrame(jpeg: ByteArray) {
        ui.post {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            if (bmp != null) ivCamera.setImageBitmap(bmp)
        }
    }

    override fun onFsList(entries: List<FsBrowser.Entry>?) {
        ui.post {
            fileList.removeAllViews()
            tvPath.text = if (curPath.isEmpty()) "/" else "/$curPath"
            if (entries == null) {
                tvStatus.setText(R.string.ra_storage_permission_needed)
                return@post
            }
            if (curPath.isNotEmpty()) addRow("..", true) { goUp() }
            for (e in entries) {
                val label = if (e.isDir) e.name else "${e.name}  (${humanSize(e.size)})"
                addRow(label, e.isDir) { if (e.isDir) openDir(e.name) else downloadFile(e.name) }
            }
        }
    }

    override fun onFsProgress(name: String, receivedBytes: Long, totalBytes: Long) {
        ui.post {
            tvStatus.text = getString(R.string.ra_downloading, name, receivedBytes / 1024, maxOf(totalBytes, 1) / 1024)
        }
    }

    override fun onFsDone(name: String) {
        ui.post { tvStatus.text = getString(R.string.ra_saved, name) }
    }

    override fun onError(message: String) {
        ui.post { tvStatus.text = message }
    }

    private fun openDir(name: String) {
        curPath = if (curPath.isEmpty()) name else "$curPath/$name"
        refreshList()
    }

    private fun goUp() {
        curPath = curPath.substringBeforeLast("/", "")
        refreshList()
    }

    private fun refreshList() {
        startService(
            Intent(this, RemoteAccessService::class.java)
                .setAction(RemoteAccessService.ACTION_FS_LIST)
                .putExtra(RemoteAccessService.EXTRA_PATH, curPath)
        )
    }

    private fun downloadFile(name: String) {
        val full = if (curPath.isEmpty()) name else "$curPath/$name"
        tvStatus.text = getString(R.string.ra_downloading, name, 0L, 0L)
        startService(
            Intent(this, RemoteAccessService::class.java)
                .setAction(RemoteAccessService.ACTION_FS_GET)
                .putExtra(RemoteAccessService.EXTRA_PATH, full)
        )
    }

    private fun addRow(text: String, isDir: Boolean, onClick: () -> Unit) {
        val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = if (isDir) "\uD83D\uDCC1 $text" else "\uD83D\uDCC4 $text"
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (6 * resources.displayMetrics.density).toInt()
        fileList.addView(b, lp)
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        return String.format("%.1f MB", kb / 1024.0)
    }
}
