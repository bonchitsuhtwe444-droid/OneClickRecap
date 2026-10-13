package com.recap.finisher.scene

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.recap.finisher.media.MediaInfo

class SceneActivity : AppCompatActivity() {

    private var videoUri: Uri? = null
    private var videoName = "-"
    private var videoMs = 0L

    @Volatile private var cancelled = false
    @Volatile private var running = false

    private lateinit var status: TextView
    private lateinit var thresholdInput: EditText
    private lateinit var list: LinearLayout

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                videoUri = uri
                videoName = MediaInfo.displayName(this, uri)
                videoMs = MediaInfo.durationMs(this, uri)
                status.text = "Video: $videoName\nကြာချိန်: ${MediaInfo.formatDuration(videoMs)}"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Scene Detection စမ်းသပ်ရန်"
            textSize = 20f
        })
        root.addView(button("1. Video ရွေး") { pickVideo.launch(arrayOf("video/*")) })

        thresholdInput = EditText(this).apply {
            hint = "Threshold (0.1 - 0.8)၊ မရိုက်ရင် 0.30"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(thresholdInput)

        root.addView(button("2. Scene ရှာ") { startDetect() })
        root.addView(button("ရပ်မယ် (Cancel)") {
            if (running) { cancelled = true; status.text = "ရပ်နေသည်..." }
            else toast("မလုပ်နေပါ")
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
            text = "Video ရွေးပါ"
        }
        root.addView(status)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }

    private fun startDetect() {
        val uri = videoUri
        if (uri == null) { toast("Video အရင်ရွေးပါ"); return }
        if (videoMs <= 0L) { toast("Video ကြာချိန် ဖတ်မရပါ"); return }
        if (running) { toast("လုပ်နေဆဲ"); return }

        val threshold = thresholdInput.text.toString().toDoubleOrNull() ?: 0.30
        if (threshold <= 0.0 || threshold >= 1.0) { toast("Threshold က 0 နဲ့ 1 ကြား"); return }

        cancelled = false
        running = true
        list.removeAllViews()
        status.text = "စတင်နေသည်... (Threshold $threshold)"
        val startTime = System.currentTimeMillis()

        Thread {
            var scenes: List<Scene>? = null
            var error: String? = null
            try {
                scenes = SceneDetector.detect(
                    context = this,
                    uri = uri,
                    durationMs = videoMs,
                    threshold = threshold,
                    isCancelled = { cancelled },
                    onProgress = { p ->
                        runOnUiThread { status.text = "ရှာနေသည်: $p%" }
                    }
                )
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                running = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val secs = (System.currentTimeMillis() - startTime) / 1000
                when {
                    error != null -> status.text = "Error: $error"
                    cancelled -> status.text = "Cancel လုပ်ပြီးပါပြီ"
                    scenes != null -> showScenes(scenes, secs)
                }
            }
        }.start()
    }

    private fun showScenes(scenes: List<Scene>, secs: Long) {
        val avg = if (scenes.isNotEmpty()) scenes.sumOf { it.durationMs } / scenes.size else 0L
        status.text = "ပြီးပါပြီ: Scene ${scenes.size} ခု၊ ပျမ်းမျှ ${avg / 1000.0} စက္ကန့်၊ အချိန် ${secs} စက္ကန့်ကြာ"
        for (s in scenes) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(54))
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (s.thumb != null) setImageBitmap(s.thumb)
            }
            val tv = TextView(this).apply {
                setPadding(dp(12), 0, 0, 0)
                text = "#${s.index}  ${fmt(s.startMs)} - ${fmt(s.endMs)}\n(${s.durationMs / 1000.0} စက္ကန့်)"
            }
            row.addView(img)
            row.addView(tv)
            list.addView(row)
        }
    }

    private fun fmt(ms: Long): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val d = (ms % 1000) / 100
        return "%d:%02d.%d".format(m, s, d)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
