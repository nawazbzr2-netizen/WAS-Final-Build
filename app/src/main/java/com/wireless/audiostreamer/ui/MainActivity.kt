package com.wireless.audiostreamer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.wireless.audiostreamer.R
import com.wireless.audiostreamer.network.DiscoveryHelper
import com.wireless.audiostreamer.network.NetUtil
import com.wireless.audiostreamer.network.PacketCipher
import com.wireless.audiostreamer.service.RemoteAccessService
import com.wireless.audiostreamer.service.StreamingService
import com.wireless.audiostreamer.util.AppPrefs

class MainActivity : BaseActivity() {

    private lateinit var tvRole: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvMyInfo: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var panelWifi: View
    private lateinit var tvNoPeers: TextView
    private lateinit var peerList: LinearLayout
    private lateinit var panelBluetooth: View
    private lateinit var tvNoBt: TextView
    private lateinit var btList: LinearLayout
    private lateinit var btnStartListen: Button
    private lateinit var btnDisconnect: Button
    private lateinit var seekVolume: SeekBar
    private lateinit var remoteAccessList: LinearLayout

    private val ui = Handler(Looper.getMainLooper())
    private var discovery: DiscoveryHelper? = null
    private var peers: List<DiscoveryHelper.Peer> = emptyList()
    private var tab = 0
    private var running = false
    private var resumed = false
    private var createdLang = "system"
    private lateinit var audioManager: AudioManager

    private class PendingPerm(val must: List<String>, val onOk: () -> Unit)
    private class StartReq(val mode: String, val peerIp: String?, val btAddr: String?)

    private var pendingPerm: PendingPerm? = null
    private var startReq: StartReq? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val p = pendingPerm
            pendingPerm = null
            if (p != null) {
                val bad = p.must.firstOrNull { !granted(it) }
                if (bad == null) p.onOk() else toast(deniedMessage(bad))
            }
        }

    private val projLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val req = startReq
            startReq = null
            val data = r.data
            if (req != null && r.resultCode == RESULT_OK && data != null) {
                launchService(req.mode, req.peerIp, req.btAddr, r.resultCode, data)
            } else {
                toast(R.string.cast_denied)
            }
        }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            applyStatus(
                intent.getBooleanExtra(StreamingService.EXTRA_RUNNING, false),
                intent.getBooleanExtra(StreamingService.EXTRA_CONNECTED, false),
                intent.getStringExtra(StreamingService.EXTRA_TEXT) ?: "",
                intent.getBooleanExtra(StreamingService.EXTRA_ERROR, false)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppPrefs.isConfigured(this)) {
            startActivity(Intent(this, RoleSetupActivity::class.java))
            finish()
            return
        }
        createdLang = AppPrefs.lang(this)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        audioManager = getSystemService(AudioManager::class.java)

        tvRole = findViewById(R.id.tvRole)
        tvStatus = findViewById(R.id.tvStatus)
        tvMyInfo = findViewById(R.id.tvMyInfo)
        tabLayout = findViewById(R.id.tabLayout)
        panelWifi = findViewById(R.id.panelWifi)
        tvNoPeers = findViewById(R.id.tvNoPeers)
        peerList = findViewById(R.id.peerList)
        panelBluetooth = findViewById(R.id.panelBluetooth)
        tvNoBt = findViewById(R.id.tvNoBt)
        btList = findViewById(R.id.btList)
        btnStartListen = findViewById(R.id.btnStartListen)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        seekVolume = findViewById(R.id.seekVolume)
        remoteAccessList = findViewById(R.id.remoteAccessList)

        // Plain (non-foreground) start: it only listens for inbound requests until a
        // capability is actually accepted, at which point it promotes itself and shows
        // the ongoing notification (see RemoteAccessService).
        startService(Intent(this, RemoteAccessService::class.java))

        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_wifi))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_bt))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = t.position
                updateUi()
            }

            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        findViewById<Button>(R.id.btnRefreshBt).setOnClickListener { refreshBt() }
        findViewById<Button>(R.id.btnBtSettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (e: Exception) {
            }
        }
        btnStartListen.setOnClickListener {
            startReceiver(if (tab == 0) StreamingService.MODE_WIFI else StreamingService.MODE_BT)
        }
        btnDisconnect.setOnClickListener {
            stopService(Intent(this, StreamingService::class.java))
        }

        seekVolume.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        seekVolume.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        if (!AppPrefs.isConfigured(this)) return
        if (AppPrefs.lang(this) != createdLang) {
            recreate()
            return
        }
        resumed = true
        ContextCompat.registerReceiver(
            this, statusReceiver, IntentFilter(StreamingService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        seekVolume.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        tvMyInfo.text = getString(
            R.string.my_ip,
            NetUtil.localIps().joinToString(", ").ifEmpty { "—" }
        )
        applyStatus(
            StreamingService.running, StreamingService.lastConnected,
            StreamingService.lastText, StreamingService.lastError
        )
        updateUi()
        renderRemoteAccess()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) { }
        stopDiscovery()
    }

    // ------------------------------------------------------------------ UI

    private fun isSender() = AppPrefs.role(this) == AppPrefs.ROLE_SENDER

    private fun updateUi() {
        val sender = isSender()
        tvRole.text = getString(
            R.string.role_fmt,
            getString(if (sender) R.string.role_sender else R.string.role_receiver)
        )
        panelWifi.visibility = if (tab == 0) View.VISIBLE else View.GONE
        panelBluetooth.visibility = if (tab == 1) View.VISIBLE else View.GONE
        btnStartListen.setText(if (tab == 0) R.string.start_listening_wifi else R.string.start_listening_bt)
        btnStartListen.visibility = if (!sender && !running) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btBar).visibility = if (sender) View.VISIBLE else View.GONE
        if (tab == 0) renderPeers() else refreshBt()
    }

    private fun applyStatus(run: Boolean, connected: Boolean, text: String, error: Boolean) {
        running = run
        tvStatus.text = if (text.isEmpty()) getString(R.string.status_idle) else text
        val color = when {
            error -> "#C62828"
            connected -> "#2E7D32"
            run -> "#F9A825"
            else -> "#888888"
        }
        tvStatus.setTextColor(Color.parseColor(color))
        btnDisconnect.visibility = if (run) View.VISIBLE else View.GONE
        btnStartListen.visibility = if (!isSender() && !run) View.VISIBLE else View.GONE
        if (run) stopDiscovery() else if (resumed) startDiscovery()
    }

    private fun renderPeers() {
        peerList.removeAllViews()
        if (!isSender()) {
            tvNoPeers.setText(R.string.receiver_wifi_hint)
            tvNoPeers.visibility = View.VISIBLE
            return
        }
        val list = peers.filter { it.role == AppPrefs.ROLE_RECEIVER }
        val manual = AppPrefs.manualIp(this)
        tvNoPeers.setText(R.string.no_peers)
        tvNoPeers.visibility = if (list.isEmpty() && manual.isBlank()) View.VISIBLE else View.GONE
        for (p in list) {
            addButton(peerList, "${p.name}\n${p.ip}") { startSender(StreamingService.MODE_WIFI, p.ip, null) }
        }
        if (manual.isNotBlank() && list.none { it.ip == manual }) {
            addButton(peerList, getString(R.string.manual_peer_fmt, manual)) {
                startSender(StreamingService.MODE_WIFI, manual, null)
            }
        }
    }

    private fun renderRemoteAccess() {
        remoteAccessList.removeAllViews()
        for (p in peers) {
            addButton(remoteAccessList, getString(R.string.ra_peer_fmt, p.name, p.ip)) {
                val i = Intent(this, RemoteControlActivity::class.java)
                i.putExtra(RemoteAccessService.EXTRA_PEER_IP, p.ip)
                i.putExtra(RemoteAccessService.EXTRA_PEER_NAME, p.name)
                startActivity(i)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshBt() {
        btList.removeAllViews()
        tvNoBt.visibility = View.GONE
        if (!isSender()) {
            tvNoBt.setText(R.string.receiver_bt_hint)
            tvNoBt.visibility = View.VISIBLE
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestPerms(
                listOf(Manifest.permission.BLUETOOTH_CONNECT),
                listOf(Manifest.permission.BLUETOOTH_CONNECT)
            ) { refreshBt() }
            return
        }
        val adapter = btAdapter()
        if (adapter == null || !adapter.isEnabled) {
            tvNoBt.setText(R.string.status_bt_off)
            tvNoBt.visibility = View.VISIBLE
            return
        }
        val devices = try { adapter.bondedDevices } catch (e: SecurityException) { null }
        if (devices.isNullOrEmpty()) {
            tvNoBt.setText(R.string.no_bt_devices)
            tvNoBt.visibility = View.VISIBLE
            return
        }
        for (d in devices) {
            val name: String = (try { d.name } catch (e: SecurityException) { null }) ?: "?"
            addButton(btList, "$name\n${d.address}") { startSender(StreamingService.MODE_BT, null, d.address) }
        }
    }

    private fun addButton(parent: LinearLayout, text: String, onClick: () -> Unit) {
        val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (8 * resources.displayMetrics.density).toInt()
        parent.addView(b, lp)
    }

    // ------------------------------------------------------------------ discovery

    private fun startDiscovery() {
        if (discovery != null || running || !AppPrefs.isConfigured(this)) return
        val d = DiscoveryHelper(applicationContext, PacketCipher(AppPrefs.pin(this)))
        d.onPeers = { list ->
            ui.post {
                peers = list
                if (resumed && tab == 0) renderPeers()
                renderRemoteAccess()
            }
        }
        d.start(AppPrefs.deviceName(), AppPrefs.role(this), AppPrefs.manualIp(this))
        discovery = d
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
        peers = emptyList()
    }

    // ------------------------------------------------------------------ start / permissions

    private fun startSender(mode: String, peerIp: String?, btAddr: String?) {
        val (all, must) = permsFor(true, mode)
        requestPerms(all, must) {
            startReq = StartReq(mode, peerIp, btAddr)
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun startReceiver(mode: String) {
        val (all, must) = permsFor(false, mode)
        requestPerms(all, must) { launchService(mode, null, null, 0, null) }
    }

    private fun permsFor(sender: Boolean, mode: String): Pair<List<String>, List<String>> {
        val all = ArrayList<String>()
        val must = ArrayList<String>()
        if (sender) {
            all.add(Manifest.permission.RECORD_AUDIO)
            must.add(Manifest.permission.RECORD_AUDIO)
        }
        if (mode == StreamingService.MODE_BT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            all.add(Manifest.permission.BLUETOOTH_CONNECT)
            must.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            all.add(Manifest.permission.POST_NOTIFICATIONS) // optional: the service also runs without it
        }
        return Pair(all, must)
    }

    private fun requestPerms(all: List<String>, must: List<String>, onOk: () -> Unit) {
        val missing = all.filter { !granted(it) }
        if (missing.isEmpty()) {
            onOk()
            return
        }
        pendingPerm = PendingPerm(must, onOk)
        permLauncher.launch(missing.toTypedArray())
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun deniedMessage(perm: String) =
        if (perm == Manifest.permission.RECORD_AUDIO) R.string.perm_audio else R.string.perm_bt

    private fun launchService(mode: String, peerIp: String?, btAddr: String?, code: Int, data: Intent?) {
        stopDiscovery()
        val i = Intent(this, StreamingService::class.java)
            .setAction(StreamingService.ACTION_START)
            .putExtra(StreamingService.EXTRA_ROLE, AppPrefs.role(this))
            .putExtra(StreamingService.EXTRA_MODE, mode)
        if (peerIp != null) i.putExtra(StreamingService.EXTRA_PEER_IP, peerIp)
        if (btAddr != null) i.putExtra(StreamingService.EXTRA_BT_ADDRESS, btAddr)
        if (data != null) {
            i.putExtra(StreamingService.EXTRA_RESULT_CODE, code)
            i.putExtra(StreamingService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, i)
        applyStatus(true, false, getString(R.string.status_starting), false)
    }

    private fun btAdapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()
}
