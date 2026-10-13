package com.recap.finisher

import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "Hello, One-Click Recap!"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        setContentView(tv)
    }
}
