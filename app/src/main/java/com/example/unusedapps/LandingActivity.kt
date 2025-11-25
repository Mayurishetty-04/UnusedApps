package com.example.unusedapps

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.view.animation.AnimationUtils

class LandingActivity : AppCompatActivity() {

    private val AUTO_NAVIGATE_MS = 0L // set to >0 if you want auto redirect

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_landing)

        // UI elements
        val title = findViewById<TextView>(R.id.titleLanding)
        val subtitle = findViewById<TextView>(R.id.subtitleLanding)
        val card = findViewById<TextView>(R.id.infoCard)
        val btnGetStarted = findViewById<Button>(R.id.btnGetStarted)
        val btnScanNow = findViewById<Button>(R.id.btnScanNow)

        // Animations
        val slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up)
        val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in)

        title.startAnimation(slideUp)
        subtitle.startAnimation(slideUp)
        card.startAnimation(fadeIn)
        btnGetStarted.startAnimation(slideUp)
        btnScanNow.startAnimation(slideUp)

        // Navigation
        btnGetStarted.setOnClickListener { openMain() }
        btnScanNow.setOnClickListener { openMain() }

        if (AUTO_NAVIGATE_MS > 0) {
            Handler(Looper.getMainLooper()).postDelayed({ openMain() }, AUTO_NAVIGATE_MS)
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
