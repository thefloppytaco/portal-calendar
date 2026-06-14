package com.portal.calendar

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Thin fullscreen host for [BoardController]. Holds the screen on, so once
 * launched (manually or by [App] after a dream bounce) the board stays up
 * until the user exits via ✕ or Back.
 */
class MainActivity : Activity() {

    private lateinit var board: BoardController
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Surface over any lock layer when relaunched out of sleep by the takeover.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        lastOrientation = resources.configuration.orientation
        attachBoard(reopenSettings = false)
        hideSystemUi()
    }

    /**
     * In "auto" orientation the activity requests SENSOR, so physically turning
     * the Portal fires a config change here (the manifest keeps us from being
     * recreated). Rebuild the board so its layout reflows tall⇄wide to match.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != lastOrientation) {
            lastOrientation = newConfig.orientation
            board.stop()
            attachBoard(reopenSettings = false)
            hideSystemUi()
        }
    }

    /**
     * Honors the configured orientation. "auto" is special: this device's
     * rotation policy ignores sensor/USER rotation for apps (it's locked as a
     * fixed display), but it DOES honor an explicit PORTRAIT/LANDSCAPE request.
     * So in auto mode we read the accelerometer ourselves ([orientListener])
     * and set an explicit lock to match how the Portal is physically turned.
     */
    private fun applyOrientation() {
        requestedOrientation = when (App.instance.store.orientation()) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            // auto: keep whatever the accelerometer listener has chosen so a
            // rebuild doesn't fight it; default to landscape until it speaks.
            else -> if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
                        requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                requestedOrientation
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    // --- accelerometer-driven auto-rotate (auto mode only) ---
    private val sensorMgr by lazy { getSystemService(SensorManager::class.java) }
    private var accelRegistered = false
    private var pendingOrient = 0
    private var pendingCount = 0

    private val orientListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val ax = abs(e.values[0]); val ay = abs(e.values[1])
            if (ax + ay < 4.5f) return // lying too flat to tell — leave as-is
            val want = when {
                ax > ay + 2f -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                ay > ax + 2f -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> return // ~45° dead zone, avoid flapping
            }
            if (want == requestedOrientation) { pendingCount = 0; return }
            // Require a few consistent readings before flipping (debounce jitter).
            if (want == pendingOrient) {
                if (++pendingCount >= 6) {
                    pendingCount = 0
                    requestedOrientation = want // explicit lock → display rotates → onConfigurationChanged → reflow
                }
            } else { pendingOrient = want; pendingCount = 1 }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun updateSensorListener() {
        val auto = App.instance.store.orientation() == "auto"
        if (auto && !accelRegistered) {
            sensorMgr?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorMgr?.registerListener(orientListener, it, SensorManager.SENSOR_DELAY_UI)
                accelRegistered = true
            }
        } else if (!auto && accelRegistered) {
            sensorMgr?.unregisterListener(orientListener); accelRegistered = false
        }
    }

    /**
     * (Re)build the board in place — one frame swap instead of an activity
     * recreate, so committing a new display size doesn't flash.
     */
    private fun attachBoard(reopenSettings: Boolean) {
        applyOrientation() // re-applied on every (re)build, incl. orientation changes
        board = BoardController(this)
        board.onExit = { goHome() }
        board.onScaleCommitted = { fromSettings ->
            board.stop()
            attachBoard(reopenSettings = fromSettings)
        }
        setContentView(board.view)
        board.start()
        if (reopenSettings) board.openSettings()
        updateSensorListener() // (de)register accel watch to match the current mode
    }

    override fun onResume() {
        super.onResume()
        updateSensorListener()
    }

    override fun onPause() {
        if (accelRegistered) { sensorMgr?.unregisterListener(orientListener); accelRegistered = false }
        super.onPause()
    }

    override fun onDestroy() {
        board.stop()
        super.onDestroy()
    }

    /** Relaunched by the idle takeover — always land on the current week. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        board.resetView()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onBackPressed() {
        if (!board.closeOverlays()) goHome()
    }

    /**
     * Explicit home intent instead of finish(): after a dream bounce,
     * Immortal's relaunched photo frame may sit directly beneath this
     * activity — finish() would reveal it instead of the launcher.
     */
    private fun goHome() {
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}
