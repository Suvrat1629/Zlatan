package com.sih26168.idr

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sih26168.idr.core.types.Mode
import com.sih26168.idr.map.OsmdroidMapRenderer
import com.sih26168.idr.map.MapRenderer
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var service: EngineService? = null
    private var bound = false
    private lateinit var mapRenderer: MapRenderer
    private lateinit var modeBadge: TextView
    private lateinit var speedText: TextView
    private lateinit var muteToggle: Button
    private lateinit var recordButton: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as EngineService.LocalBinder).service()
            bound = true
            observeEngineState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            startAndBindEngineService()
        } else {

            System.err.println("[MainActivity] " + getString(R.string.permission_rationale))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mapRenderer = OsmdroidMapRenderer(this)
        mapRenderer.attach(findViewById(R.id.map_container))

        modeBadge = findViewById(R.id.mode_badge)
        speedText = findViewById(R.id.speed_text)
        muteToggle = findViewById(R.id.mute_toggle)
        recordButton = findViewById(R.id.record_button)

        muteToggle.setOnClickListener {
            val newlyMuted = !muteToggle.isSelected
            service?.setGnssMuted(newlyMuted)
            muteToggle.isSelected = newlyMuted
            muteToggle.text = getString(
                if (newlyMuted) R.string.gnss_unmute_toggle else R.string.gnss_mute_toggle
            )
        }
        recordButton.setOnClickListener {
            val recording = service?.toggleRecording() ?: false
            recordButton.text = getString(if (recording) R.string.stop_recording else R.string.record_trip)
        }
        findViewById<ImageButton>(R.id.recenter_button).setOnClickListener {
            mapRenderer.recenter()
        }

        requestNeededPermissionsThenStart()
    }

    private fun requestNeededPermissionsThenStart() {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startAndBindEngineService() else requestPermissionsLauncher.launch(missing.toTypedArray())
    }

    private fun startAndBindEngineService() {
        val intent = Intent(this, EngineService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun observeEngineState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                service?.engine?.state?.collect { s ->

                    mapRenderer.updatePosition(s.lat, s.lon, s.headingDeg)
                    mapRenderer.appendTrailPoint(s.lat, s.lon, s.mode)

                    modeBadge.text = buildString {
                        append(modeLabel(s.mode))
                        append("  ")
                        append(s.satsInFix)
                        append(" sats")
                        if (s.irnssSatsInFix > 0) append(" (NavIC ${s.irnssSatsInFix})")
                    }
                    speedText.text = getString(R.string.speed_format, s.speedMps * 3.6f)
                    service?.updateNotification(s.mode)
                }
            }
        }
    }

    private fun modeLabel(mode: Mode): String = when (mode) {
        Mode.INIT -> getString(R.string.mode_init)
        Mode.NAVIC -> getString(R.string.mode_navic)
        Mode.GNSS -> getString(R.string.mode_gnss)
        Mode.DEGRADED -> getString(R.string.mode_degraded)
        Mode.DEAD_RECKONING -> getString(R.string.mode_dead_reckoning)
    }

    override fun onResume() {
        super.onResume()
        mapRenderer.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapRenderer.onPause()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
