package com.wireless.audiostreamer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.wireless.audiostreamer.R
import com.wireless.audiostreamer.network.CtrlProtocol
import com.wireless.audiostreamer.service.RemoteAccessService

/**
 * Full-screen Accept/Decline prompt shown on the phone being asked for access — rings and
 * vibrates like an incoming call. Nothing is shared unless the person taps Accept here.
 */
class IncomingRequestActivity : BaseActivity() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var pendingCap = 0

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) sendAccept(pendingCap) else {
            Toast.makeText(this, R.string.ra_permission_needed, Toast.LENGTH_LONG).show()
            respond(false, pendingCap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        setContentView(R.layout.activity_incoming_request)

        val peerName = intent.getStringExtra(RemoteAccessService.EXTRA_PEER_NAME) ?: "?"
        val cap = intent.getIntExtra(RemoteAccessService.EXTRA_CAP, 0)

        findViewById<TextView>(R.id.tvRequestText).text =
            getString(R.string.ra_request_title, peerName) + "\n" + getString(capLabel(cap))

        findViewById<Button>(R.id.btnAccept).setOnClickListener { onAcceptTapped(cap) }
        findViewById<Button>(R.id.btnDecline).setOnClickListener { respond(false, cap) }
        startAlert()
    }

    private fun onAcceptTapped(cap: Int) {
        pendingCap = cap
        val needed = when (cap) {
            CtrlProtocol.CAP_MIC -> Manifest.permission.RECORD_AUDIO
            CtrlProtocol.CAP_CAM_FRONT, CtrlProtocol.CAP_CAM_BACK -> Manifest.permission.CAMERA
            else -> null
        }
        if (needed != null && ContextCompat.checkSelfPermission(this, needed) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(needed)
        } else {
            sendAccept(cap)
        }
    }

    private fun sendAccept(cap: Int) = respond(true, cap)

    private fun respond(accept: Boolean, cap: Int) {
        stopAlert()
        startService(
            Intent(this, RemoteAccessService::class.java)
                .setAction(RemoteAccessService.ACTION_RESPOND)
                .putExtra(RemoteAccessService.EXTRA_ACCEPT, accept)
                .putExtra(RemoteAccessService.EXTRA_CAP, cap)
        )
        finish()
    }

    private fun startAlert() {
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(this, uri)
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            r.play()
            ringtone = r
        } catch (e: Exception) {
        }
        try {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vibrator = v
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0))
        } catch (e: Exception) {
        }
    }

    private fun stopAlert() {
        try { ringtone?.stop() } catch (e: Exception) { }
        try { vibrator?.cancel() } catch (e: Exception) { }
    }

    override fun onDestroy() {
        stopAlert()
        super.onDestroy()
    }

    private fun capLabel(cap: Int) = when (cap) {
        CtrlProtocol.CAP_MIC -> R.string.cap_mic
        CtrlProtocol.CAP_CAM_FRONT -> R.string.cap_cam_front
        CtrlProtocol.CAP_CAM_BACK -> R.string.cap_cam_back
        else -> R.string.cap_storage
    }
}
