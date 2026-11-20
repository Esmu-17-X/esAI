package com.esmu.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("esmu", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(ImageView(this).apply { setImageResource(R.drawable.esmu) },
            LinearLayout.LayoutParams(dp(120), dp(120)))
        root.addView(TextView(this).apply {
            text = "ESMU"; textSize = 28f; gravity = Gravity.CENTER
        })

        val keyInput = EditText(this).apply {
            hint = if (BuildConfig.API_KEY.isNotBlank()) "Key built in - paste here to override"
                   else "Paste your Anthropic API key here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("api_key", ""))
        }
        root.addView(keyInput, LinearLayout.LayoutParams(-1, -2))

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { onClick() }
            root.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

        button("1. Save API key") {
            prefs.edit().putString("api_key", keyInput.text.toString().trim()).apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        button("2. Allow display over other apps") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            } else Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show()
        }
        button("3. Start ESMU") {
            val typed = keyInput.text.toString().trim()
            if (typed.isNotEmpty()) prefs.edit().putString("api_key", typed).apply()
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow overlay permission first", Toast.LENGTH_LONG).show()
            } else {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                startForegroundService(Intent(this, OverlayService::class.java))
            }
        }
        button("Stop ESMU") { stopService(Intent(this, OverlayService::class.java)) }

        setContentView(ScrollView(this).apply { addView(root) })
    }
}