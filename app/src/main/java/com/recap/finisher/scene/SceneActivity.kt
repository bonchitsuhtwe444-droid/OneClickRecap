package com.recap.finisher.scene

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.recap.finisher.media.MediaInfo
import com.recap.finisher.subtitle.SrtCue
import com.recap.finisher.subtitle.SrtParser
import com.recap.finisher.subtitle.SrtTimeTools
import java.util.Locale
import kotlin.math.abs

class SceneActivity : AppCompatActivity() {

    private var cues: List<SrtCue> = emptyList()
    private var mp3Ms = 0L
    private var videoMs = 0L
    private var videoUri: Uri? = null
    private var scenes: List<Scene> = emptyList()
    private var scenesFor: Uri? = null
    @Volatile private var busy = false
    @Volatile private var cancelled = false

    private lateinit var srtInput: EditText
    private lateinit var anchorInput: EditText
    private lateinit var speedInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var resultInput: EditText
    private lateinit var status: TextView

    private val pickMp3 = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { mp3Ms = MediaInfo.durationMs(this, u); say("MP3: ${MediaInfo.displayName(this, u)} (${mp3Ms / 1000}s)") }
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { videoUri = u; videoMs = MediaInfo.durationMs(this, u); say("Video: ${MediaInfo.displayName(this, u)} (${videoMs / 1000}s)") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("recap", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }

        root.addView(TextView(this).apply { text = "Smart Match (Anchor + Gemini)"; textSize = 20f })
        srtInput = edit("SRT စာသား Paste လုပ်ပါ", 5, true)
        root.addView(srtInput)
        root.addView(btn("TTS MP3 ရွေး") { pickMp3.launch(arrayOf("audio/*")) })
        root.addView(btn("Movie Video ရွေး") { pickVideo.launch(arrayOf("video/*")) })

        speedInput = edit("Speed: 0.5 = 2 ဆနှေး၊ 1 = ပုံမှန်", 1, false).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getFloat("speed", 0.5f).toString())
        }
        root.addView(speedInput)

        anchorInput = edit("Anchor တစ်ကြောင်းစီ: Narration စက္ကန့်-Movie စက္ကန့်\nဗလာထားရင် Offline အလိုအလျောက် (အချိန်အချိုး)", 4, true)
        root.addView(anchorInput)
        root.addView(btn("Anchor နဲ့ ရွေး (Offline၊ ဗလာလည်းရ)") { generate() })

        keyInput = edit("Gemini API Key (Optional)", 1, false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        modelInput = edit("Model", 1, false).apply { setText(prefs.getString("model", "gemini-2.5-flash")) }
        root.addView(keyInput)
        root.addView(modelInput)
        root.addView(btn("Gemini နဲ့ Anchor ရှာ + ရွေး") {
            prefs.edit().putString("key", keyInput.text.toString().trim())
                .putString("model", modelInput.text.toString().trim()).apply()
            gemini()
        })
        root.addView(btn("ရပ်မယ် (Cancel)") { cancelled = true; say("ရပ်နေသည်...") })

        status = TextView(this).apply { textSize = 14f; setPadding(0, dp(12), 0, dp(12)); text = "SRT၊ MP3၊ Video ထည့်ပါ" }
        root.addView(status)
        resultInput = edit("ရလဒ် Segment စာရင်း ဒီမှာပေါ်မယ်", 6, true)
        root.addView(resultInput)
        root.addView(btn("ရလဒ်ကို Copy (Main App ရဲ့ Segment ကွက်မှာ Paste)") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("segments", resultInput.text.toString()))
            toast("Copy ပြီးပါပြီ")
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onDestroy() { cancelled = true; super.onDestroy() }

    private fun readSpeed(): Double? {
        val s = speedInput.text.toString().trim().toDoubleOrNull()
        if (s == null || s < 0.25 || s > 2.0) { toast("Speed က 0.25 နဲ့ 2 ကြား ထည့်ပါ"); return null }
        return s
    }

    private fun ready(): Boolean {
        if (busy) { toast("လုပ်နေဆဲ"); return false }
        if (readSpeed() == null) return false
        val parsed = SrtParser.parse(srtInput.text.toString()).cues
        if (parsed.isEmpty()) { toast("SRT အရင်ထည့်ပါ"); return false }
        if (mp3Ms <= 0L) { toast("MP3 အရင်ရွေးပါ"); return false }
        if (videoUri == null || videoMs <= 0L) { toast("Video အရင်ရွေးပါ"); return false }
        val srtMs = parsed.maxOf { it.endMs }
        cues = if (abs(srtMs - mp3Ms) * 100.0 / mp3Ms > 1.0) SrtTimeTools.scaleToDuration(parsed, mp3Ms) else parsed
        return true
    }

    private fun ensureScenes(next: () -> Unit) {
        val uri = videoUri ?: return
        if (scenesFor == uri && scenes.isNotEmpty()) { next(); return }
        busy = true
        cancelled = false
        Thread {
            var found: List<Scene> = emptyList()
            var err: String? = null
            try {
                found = SceneDetector.detect(this, uri, videoMs, 0.30, 500L, 1500L, { cancelled }) { p ->
                    runOnUiThread { say("Scene ရှာနေသည်: $p%") }
                }
            } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (err != null) say("Error: $err")
                else if (cancelled || found.isEmpty()) say("Scene မရပါ/ရပ်ထားသည်")
                else { scenes = found; scenesFor = uri; next() }
            }
        }.start()
    }

    private fun generate() {
        if (!ready()) return
        ensureScenes { runMatch() }
    }

    private fun parseAnchors(): List<Anchor> {
        val list = mutableListOf<Anchor>()
        for (line in anchorInput.text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val p = line.split(Regex("[-,\\s]+"))
            val a = p.getOrNull(0)?.toDoubleOrNull()
            val b = p.getOrNull(1)?.toDoubleOrNull()
            if (a != null && b != null) list.add(Anchor((a * 1000).toLong(), (b * 1000).toLong()))
        }
        return list
    }

    private fun runMatch() {
        val speed = readSpeed() ?: return
        // Export (RecapExporter) က ဒီတန်ဖိုးကို ဖတ်သုံးမည်
        getSharedPreferences("recap", Context.MODE_PRIVATE).edit().putFloat("speed", speed.toFloat()).apply()

        val r = AnchorMatcher.match(cues, scenes, mp3Ms, videoMs, parseAnchors(), speed)
        if (r.ranges.isEmpty()) { say(r.warnings.joinToString("\n")); return }
        resultInput.setText(r.ranges.joinToString("\n") { fmt(it.startMs) + "-" + fmt(it.endMs) })
        val srcTotal = r.ranges.sumOf { it.durationMs } / 1000
        val outTotal = (r.ranges.sumOf { it.durationMs } / speed).toLong() / 1000
        val sb = StringBuilder("✓ Scene ${scenes.size} ခု၊ Segment ${r.ranges.size} ခု\n")
        sb.append("Movie ကနေ ယူတာ ${srcTotal}s → Speed ${speed}x နဲ့ ${outTotal}s၊ MP3 ${mp3Ms / 1000}s\n")
        sb.append("Speed ကို Main App Export မှာ အလိုအလျောက်သုံးမည်။ ပုံမှန်ပြန်လုပ်ချင်ရင် Speed=1 ထည့်ပြီး ပြန်ရွေးပါ။\n")
        sb.append(if (r.warnings.isEmpty()) "သတိပေးချက် မရှိ" else "⚠ " + r.warnings.take(8).joinToString("\n• ", "\n• "))
        say(sb.toString())
    }

    private fun gemini() {
        if (!ready()) return
        val key = keyInput.text.toString().trim()
        val model = modelInput.text.toString().trim()
        if (key.isEmpty() || model.isEmpty()) { toast("API Key နဲ့ Model ထည့်ပါ"); return }
        ensureScenes {
            busy = true
            say("Gemini ကို မေးနေသည်... (Thumbnail ${scenes.size} ပုံ ပို့သည်)")
            Thread {
                var anchors: List<Anchor> = emptyList()
                var err: String? = null
                try { anchors = GeminiAnchors.request(key, model, cues, scenes, mp3Ms, videoMs) }
                catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
                runOnUiThread {
                    busy = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (err != null) { say("Gemini Error: $err\n(Anchor ကိုယ်တိုင်ထည့်ပြီး Offline နည်းသုံးလို့ရသည်)"); return@runOnUiThread }
                    anchorInput.setText(anchors.joinToString("\n") { "${it.narrMs / 1000.0}-${it.movieMs / 1000.0}" })
                    runMatch()
                }
            }.start()
        }
    }

    private fun fmt(ms: Long) = String.format(Locale.US, "%.3f", ms / 1000.0)
    private fun say(s: String) { status.text = s }

    private fun edit(h: String, lines: Int, multi: Boolean) = EditText(this).apply {
        hint = h
        minLines = lines
        gravity = Gravity.TOP
        inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_TEXT
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
