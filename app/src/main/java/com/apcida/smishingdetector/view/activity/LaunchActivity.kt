package com.apcida.smishingdetector.view.activity

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.apcida.smishingdetector.R

/** Plays the deText intro for a fresh launcher start, then leaves the task's back stack. */
class LaunchActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var videoView: VideoView
    private var completed = false
    private var leftForeground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Some launchers create this activity again when bringing an existing task forward.
        if (!isTaskRoot) {
            finish()
            return
        }

        // A restored launch screen should never restart the animation.
        if (savedInstanceState != null) {
            openMain()
            return
        }

        setContentView(R.layout.activity_launch)
        videoView = findViewById(R.id.launch_video)
        val placeholder = findViewById<View>(R.id.launch_placeholder)

        videoView.setOnPreparedListener { player ->
            if (completed || leftForeground) return@setOnPreparedListener
            player.isLooping = false
            player.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    placeholder.visibility = View.GONE
                }
                false
            }
            videoView.start()
        }
        videoView.setOnCompletionListener { openMain() }
        videoView.setOnErrorListener { _, _, _ ->
            openMain()
            true
        }

        // Never strand the user on the intro if media playback stalls.
        handler.postDelayed(::openMain, 7_000L)
        videoView.setVideoURI(
            Uri.parse("android.resource://$packageName/${R.raw.detext_intro}")
        )
    }

    override fun onPause() {
        super.onPause()
        if (!completed) {
            leftForeground = true
            handler.removeCallbacksAndMessages(null)
            if (::videoView.isInitialized) videoView.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (leftForeground && !completed) openMain()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::videoView.isInitialized) videoView.stopPlayback()
        super.onDestroy()
    }

    private fun openMain() {
        if (completed) return
        completed = true
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(0, 0)
    }
}
