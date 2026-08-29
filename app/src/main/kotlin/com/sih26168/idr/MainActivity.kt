package com.sih26168.idr

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sih26168.idr.core.types.Geo
import com.sih26168.idr.core.types.LatLon
import com.sih26168.idr.core.types.Mode
import com.sih26168.idr.core.types.PositionState
import com.sih26168.idr.map.MapRenderer
import com.sih26168.idr.map.OsmdroidMapRenderer
import com.sih26168.idr.map.PositionInterpolator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var service: EngineService? = null
    private var bound = false
    private lateinit var mapRenderer: MapRenderer
    private lateinit var modeBadge: TextView
    private lateinit var modeAccent: View
    private lateinit var speedText: TextView
    private lateinit var driftText: TextView
    private lateinit var muteToggle: Button
    private lateinit var recordButton: Button
    private lateinit var compareToggle: Button
    private lateinit var permissionOverlay: View
    private lateinit var permissionMessage: TextView
    private lateinit var grantPermissionButton: Button

    private val positionInterpolator = PositionInterpolator()
    private var latestUncertaintyM = 0f

    private var blackoutStartPosition: LatLon? = null
    private var blackoutDistanceM = 0.0
    private var lastMode: Mode? = null

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
            permissionOverlay.visibility = View.GONE
            startAndBindEngineService()
        } else {
            showPermissionDeniedUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        mapRenderer = OsmdroidMapRenderer(this)
        mapRenderer.attach(findViewById(R.id.map_container))

        modeBadge = findViewById(R.id.mode_badge)
        modeAccent = findViewById(R.id.mode_accent)
        speedText = findViewById(R.id.speed_text)
        driftText = findViewById(R.id.drift_text)
        applyWindowInsets()
        muteToggle = findViewById(R.id.mute_toggle)
        recordButton = findViewById(R.id.record_button)
        compareToggle = findViewById(R.id.compare_toggle)
        permissionOverlay = findViewById(R.id.permission_overlay)
        permissionMessage = findViewById(R.id.permission_message)
        grantPermissionButton = findViewById(R.id.grant_permission_button)

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
        compareToggle.setOnClickListener {
            val enabled = !compareToggle.isSelected
            compareToggle.isSelected = enabled
            mapRenderer.setCompareMode(enabled)
        }
        findViewById<ImageButton>(R.id.recenter_button).setOnClickListener {
            mapRenderer.recenter()
        }

        requestNeededPermissionsThenStart()
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestNeededPermissionsThenStart() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionOverlay.visibility = View.GONE
            startAndBindEngineService()
        } else {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun showPermissionDeniedUi() {
        val permanentlyDenied = requiredPermissions().any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED &&
                !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        permissionOverlay.visibility = View.VISIBLE
        if (permanentlyDenied) {
            permissionMessage.text = getString(R.string.permission_denied_permanently)
            grantPermissionButton.text = getString(R.string.open_settings)
            grantPermissionButton.setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
        } else {
            permissionMessage.text = getString(R.string.permission_rationale)
            grantPermissionButton.text = getString(R.string.grant_permission)
            grantPermissionButton.setOnClickListener { requestNeededPermissionsThenStart() }
        }
    }

    private fun startAndBindEngineService() {
        val intent = Intent(this, EngineService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun observeEngineState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    service?.engine?.state?.collect { s ->
                        positionInterpolator.push(s.lat, s.lon, s.headingDeg)
                        latestUncertaintyM = s.uncertaintyM
                        mapRenderer.appendTrailPoint(s.lat, s.lon, s.mode)
                        if (mapRenderer.isGnssFamily(s.mode)) {
                            mapRenderer.appendPlainGpsPoint(s.lat, s.lon)
                        }
                        updateBlackoutTracking(s)

                        modeBadge.text = buildString {
                            append(modeLabel(s.mode))
                            append("  ")
                            append(s.satsInFix)
                            append(" sats")
                            if (s.irnssSatsInFix > 0) append(" (NavIC ${s.irnssSatsInFix})")
                        }
                        modeAccent.setBackgroundColor(
                            ContextCompat.getColor(this@MainActivity, modeAccentColor(s.mode))
                        )
                        speedText.text = getString(R.string.speed_format, s.speedMps * 3.6f)
                        service?.updateNotification(s.mode)
                    }
                }
                launch {
                    while (isActive) {
                        positionInterpolator.interpolate()?.let {
                            mapRenderer.updatePosition(it.lat, it.lon, it.headingDeg, latestUncertaintyM)
                        }
                        delay(16)
                    }
                }
            }
        }
    }

    private fun updateBlackoutTracking(s: PositionState) {
        if (s.mode == Mode.DEAD_RECKONING) {
            val here = LatLon(s.lat, s.lon)
            if (lastMode != Mode.DEAD_RECKONING) {
                blackoutStartPosition = here
                blackoutDistanceM = 0.0
            } else {
                blackoutStartPosition?.let { blackoutDistanceM += Geo.distanceM(it, here) }
                blackoutStartPosition = here
            }
            driftText.visibility = View.VISIBLE
            driftText.text = if (blackoutDistanceM > 1.0) {
                val driftPct = (s.uncertaintyM / blackoutDistanceM * 100.0).coerceIn(0.0, 999.0)
                getString(R.string.drift_format, driftPct, blackoutDistanceM)
            } else {
                getString(R.string.drift_unknown)
            }
        } else {
            driftText.visibility = View.GONE
            blackoutStartPosition = null
            blackoutDistanceM = 0.0
        }
        lastMode = s.mode
    }

    private fun modeLabel(mode: Mode): String = when (mode) {
        Mode.INIT -> getString(R.string.mode_init)
        Mode.NAVIC -> getString(R.string.mode_navic)
        Mode.GNSS -> getString(R.string.mode_gnss)
        Mode.DEGRADED -> getString(R.string.mode_degraded)
        Mode.DEAD_RECKONING -> getString(R.string.mode_dead_reckoning)
    }

    /** Mode palette (semantic, stable across day/night) — see idr-android-ui §4. */
    private fun modeAccentColor(mode: Mode): Int = when (mode) {
        Mode.INIT -> R.color.idr_mode_init
        Mode.GNSS -> R.color.idr_mode_gnss
        Mode.NAVIC -> R.color.idr_navic
        Mode.DEGRADED, Mode.DEAD_RECKONING -> R.color.idr_dead_reckoning
    }

    /**
     * Edge-to-edge: the map draws full-bleed under the system bars; only the
     * chrome is inset. No android:fitsSystemWindows in XML — see idr-android-ui §7.
     */
    private fun applyWindowInsets() {
        val baseMargin = resources.getDimensionPixelSize(R.dimen.spacing_medium)
        val modeCard = findViewById<View>(R.id.mode_badge_card)
        val speedCard = findViewById<View>(R.id.speed_card)
        val actionBarContent = findViewById<View>(R.id.action_bar_content)
        val basePadV = resources.getDimensionPixelSize(R.dimen.spacing_small)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            modeCard.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = baseMargin + bars.top
            }
            speedCard.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = baseMargin + bars.top
            }
            actionBarContent.setPadding(
                actionBarContent.paddingLeft,
                basePadV,
                actionBarContent.paddingRight,
                basePadV + bars.bottom,
            )
            insets
        }
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
