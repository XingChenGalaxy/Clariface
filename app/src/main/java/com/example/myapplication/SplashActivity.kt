package com.example.myapplication

import android.content.Intent
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val glowView = findViewById<android.view.View>(R.id.splashGlow)
        glowView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.splash_glow_pulse))
        findViewById<ImageView>(R.id.splashIcon).startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.splash_icon_in)
        )
        val titleView = findViewById<TextView>(R.id.splashTitle)
        applyTitleGradient(titleView)
        titleView.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.splash_text_in)
        )

        handler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, 1500L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun applyTitleGradient(titleView: TextView) {
        titleView.post {
            val shader = LinearGradient(
                0f,
                0f,
                titleView.width.toFloat(),
                titleView.height.toFloat(),
                intArrayOf(getColor(R.color.splash_gold_start), getColor(R.color.splash_gold_end)),
                null,
                Shader.TileMode.CLAMP
            )
            titleView.paint.shader = shader
            titleView.invalidate()
        }
    }
}

