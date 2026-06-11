package com.portal.calendar

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/**
 * Thin fullscreen host for [BoardController]. Holds the screen on, so once
 * launched (manually or by [App] after a dream bounce) the board stays up
 * until the user exits via ✕ or Back.
 */
class MainActivity : Activity() {

    private lateinit var board: BoardController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Surface over any lock layer when relaunched out of sleep by the takeover.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        attachBoard(reopenSettings = false)
        hideSystemUi()
    }

    /**
     * (Re)build the board in place — one frame swap instead of an activity
     * recreate, so committing a new display size doesn't flash.
     */
    private fun attachBoard(reopenSettings: Boolean) {
        board = BoardController(this)
        board.onExit = { goHome() }
        board.onScaleCommitted = { fromSettings ->
            board.stop()
            attachBoard(reopenSettings = fromSettings)
        }
        setContentView(board.view)
        board.start()
        if (reopenSettings) board.openSettings()
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
