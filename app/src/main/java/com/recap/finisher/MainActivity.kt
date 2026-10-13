package com.recap.finisher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.recap.finisher.export.BurnIn
import com.recap.finisher.export.RecapExporter
import com.recap.finisher.export.SegmentExporter
import com.recap.finisher.export.TimeRange
import com.recap.finisher.media.MediaInfo
import com.recap.finisher.scene.Scene
import com.recap.finisher.scene.SceneActivity
import com.recap.finisher.scene.SceneDetector
import com.recap.finisher.scene.SceneMatcher
import com.recap.finisher.subtitle.SrtCue
import com.recap.finisher.subtitle.SrtIssue
import com.recap.finisher.subtitle.SrtParser
import com.recap.finisher.subtitle.SrtTimeTools
import com.recap.finisher.subtitle.VideoSize
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private var cues: List<SrtCue> = emptyList()
    private var issues: List<SrtIssue> = emptyList()
    private var srtName = "-"
    private var mp3Name = "-"
    private var videoName = "-"
    private var mp3Ms = 0L
    private var videoMs = 0L
    private var videoUri: Uri? = null
    private var mp3Uri: Uri? = null

    private var exportStatus = ""
    private var recapStatus = ""
    private var matchStatus = ""
    private var segmentFile: File? = null
    private var recapFile: File? = null
    private var savingRecap = false

    // Scene Matching
    private var scenes: List<Scene> = emptyList()
    private var scenesFor: Uri? = null
    @Volatile private var matchCancelled = false
    @Volatile private var matchRunning = false

    private lateinit var infoView: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var rangesInput: EditText
    private lateinit var srtInput: EditText
    private lateinit var burnCheck: CheckBox
    private lateinit var exporter: SegmentExporter
    private lateinit var recapExporter: RecapExporter

    private val pickSrt =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadSrt(uri)
        }

    private val pickMp3 =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                mp3Uri = uri
                mp3Name = MediaInfo.displayName(this, uri)
                mp3Ms = MediaInfo.durationMs(this, uri)
                if (mp3Ms == 0L) toast("MP3 Duration ဖတ်မရပါ")
                refresh()
            }
        }

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                videoUri = uri
                videoName = MediaInfo.displayName(this, uri)
                videoMs = MediaInfo.durationMs(this, uri)
                if (videoMs == 0L) toast("Video Duration ဖတ်မရပါ")
                refresh()
            }
        }

    private val saveSrt =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/x-subrip")
        ) { uri ->
            if (uri != null) writeSrt(uri)
        }

    private val saveMp4 =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("video/mp4")
        ) { uri ->
            if (uri != null) {
                copyFileTo(if (savingRecap) recapFile else segmentFile, uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exporter = SegmentExporter(this)
        recapExporter = RecapExporter(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(title("One-Click Recap"))
        root.addView(button("🎬 Scene Test ဖွင့်") {
            startActivity(Intent(this, SceneActivity::class.java))
        })

        srtInput = EditText(this).apply {
            hint = "ဒီမှာ SRT စာသား Paste လုပ်ပါ\n\n1\n00:00:01,000 --> 00:00:04,000\nစာတန်းစာသား"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            maxLines = 10
            gravity = Gravity.TOP
        }
        root.addView(srtInput)
        root.addView(button("1. SRT စာသားကို အသုံးပြု") { loadSrtText() })
        root.addView(button("(ဒါမှမဟုတ်) SRT ဖိုင်ရွေး") { pickSrt.launch(arrayOf("*/*")) })

        root.addView(button("2. TTS MP3 ရွေး") { pickMp3.launch(arrayOf("audio/*")) })
        root.addView(button("3. Movie Video ရွေး") { pickVideo.launch(arrayOf("video/*")) })

        root.addView(title("Timing ပြင်ရန်"))
        root.addView(button("MP3 Duration နဲ့ ကိုက်အောင် Scale လုပ်") { doScale() })
        root.addView(button("အချိန်ထပ်တာ/Error ပြင်") { doFix() })
        root.addView(button("-100 ms ရွှေ့") { doShift(-100) })
        root.addView(button("+100 ms ရွှေ့") { doShift(100) })
        root.addView(button("ပြင်ပြီး SRT သိမ်း") { doSave() })

        root.addView(title("Segment တစ်ခုဖြတ် (စမ်းသပ်)"))
        startInput = numberInput("Start (စက္ကန့်) ဥပမာ 10")
        endInput = numberInput("End (စက္ကန့်) ဥပမာ 25")
        root.addView(startInput)
        root.addView(endInput)
        root.addView(button("Segment ဖြတ်ထုတ်") { doExport() })
        root.addView(button("ဖြတ်ပြီးဖိုင်ကို MP4 အဖြစ်သိမ်း") { doSaveSegment() })

        root.addView(title("Segment များပေါင်း + MP3 + စာတန်း"))
        root.addView(button("🤖 Scene ရှာ + အလိုအလျောက်ရွေး") { doAutoMatch() })
        rangesInput = EditText(this).apply {
            hint = "တစ်ကြောင်းကို Segment တစ်ခု (စက္ကန့်)\nအလိုအလျောက်ရွေးရင် ဒီမှာ ဖြည့်ပေးမယ်\nကိုယ်တိုင်လည်း ရိုက်လို့ရတယ်\n5-12\n40-48.5"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP
        }
        root.addView(rangesInput)
        burnCheck = CheckBox(this).apply { text = "စာတန်း (SRT) ကို Video ပေါ် Burn-in လုပ်မယ်" }
        root.addView(burnCheck)
        root.addView(button("ပေါင်း + MP3 ထည့်ပြီး Export") { doRecap() })
        root.addView(button("ပေါင်းပြီး MP4 ကို သိမ်း") { doSaveRecap() })

        root.addView(button("Export/Scene ရှာတာ အားလုံး ရပ်မယ် (Cancel)") { doCancel() })

        infoView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(16), 0, 0)
            setTextIsSelectable(true)
        }
        root.addView(infoView)

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onDestroy() {
        matchCancelled = true
        if (exporter.isRunning) exporter.cancel()
        if (recapExporter.isRunning) recapExporter.cancel()
        super.onDestroy()
    }

    // ---------- SRT ဖတ်ခြင်း ----------

    private fun loadSrt(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: run { toast("ဖိုင်ဖွင့်မရပါ"); return }
            val result = SrtParser.parse(raw)
            cues = result.cues
            issues = result.issues
            srtName = MediaInfo.displayName(this, uri)
            refresh()
        } catch (e: Exception) {
            toast("SRT ဖတ်ရာတွင် Error: ${e.message}")
        }
    }

    private fun loadSrtText() {
        val raw = srtInput.text.toString()
        if (raw.isBlank()) { toast("SRT စာသား အရင်ထည့်ပါ"); return }
        val result = SrtParser.parse(raw)
        cues = result.cues
        issues = result.issues
        srtName = "(Paste လုပ်ထားသော စာသား)"
        refresh()
    }

    // ---------- Timing ပြင်ခြင်း ----------

    private fun requireCues(): Boolean {
        if (cues.isEmpty() && srtInput.text.isNotBlank()) loadSrtText()
        if (cues.isEmpty()) { toast("SRT အရင်ထည့်ပါ"); return false }
        return true
    }

    private fun doScale() {
        if (!requireCues()) return
        if (mp3Ms <= 0L) { toast("MP3 အရင်ရွေးပါ"); return }
        cues = SrtTimeTools.scaleToDuration(cues, mp3Ms)
        issues = SrtParser.validate(cues)
        refresh()
    }

    private fun doFix() {
        if (!requireCues()) return
        cues = SrtTimeTools.fixOverlaps(cues)
        issues = SrtParser.validate(cues)
        refresh()
    }

    private fun doShift(offset: Long) {
        if (!requireCues()) return
        cues = SrtTimeTools.shift(cues, offset)
        issues = SrtParser.validate(cues)
        refresh()
    }

    private fun doSave() {
        if (!requireCues()) return
        saveSrt.launch("fixed.srt")
    }

    private fun writeSrt(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(SrtTimeTools.toSrt(cues))
            }
            toast("သိမ်းပြီးပါပြီ")
        } catch (e: Exception) {
            toast("သိမ်းရာတွင် Error: ${e.message}")
        }
    }

    // ---------- Segment တစ်ခုဖြတ်ခြင်း ----------

    private fun doExport() {
        val uri = videoUri
        if (uri == null) { toast("Video အရင်ရွေးပါ"); return }

        val startSec = startInput.text.toString().toDoubleOrNull()
        val endSec = endInput.text.toString().toDoubleOrNull()
        if (startSec == null || endSec == null) { toast("Start/End ကို ဂဏန်းနဲ့ ရိုက်ပါ"); return }
        if (startSec < 0 || endSec <= startSec) { toast("End က Start ထက်ကြီးရမယ်"); return }

        val startMs = (startSec * 1000).toLong()
        val endMs = (endSec * 1000).toLong()
        if (videoMs > 0L && endMs > videoMs) {
            toast("End က Video ကြာချိန် (${videoMs / 1000} စက္ကန့်) ထက်မကျော်ရ")
            return
        }
        if (recapExporter.isRunning) { toast("ပေါင်း Export လုပ်နေဆဲ"); return }

        val out = File(cacheDir, "segment.mp4")
        segmentFile = null
        exportStatus = "စတင်နေသည်..."
        refresh()

        exporter.export(
            videoUri = uri,
            startMs = startMs,
            endMs = endMs,
            output = out,
            onProgress = { p ->
                exportStatus = "Export လုပ်နေသည်: $p%"
                refresh()
            },
            onFinished = { ok, msg ->
                exportStatus = msg
                if (ok) segmentFile = out
                refresh()
            }
        )
    }

    private fun doSaveSegment() {
        val f = segmentFile
        if (f == null || !f.exists()) { toast("ဖြတ်ပြီးဖိုင် မရှိသေးပါ"); return }
        savingRecap = false
        saveMp4.launch("segment.mp4")
    }

    // ---------- Scene ရှာ + အလိုအလျောက်ရွေး ----------

    private fun doAutoMatch() {
        val vUri = videoUri
        if (vUri == null) { toast("Video အရင်ရွေးပါ"); return }
        if (!requireCues()) return
        if (mp3Ms <= 0L) { toast("MP3 အရင်ရွေးပါ"); return }
        if (videoMs <= 0L) { toast("Video ကြာချိန် ဖတ်မရပါ"); return }
        if (matchRunning) { toast("Scene ရှာနေဆဲ"); return }
        if (exporter.isRunning || recapExporter.isRunning) { toast("Export လုပ်နေဆဲ"); return }

        // ဒီ Video အတွက် Scene ရှာပြီးသားဆိုရင် ပြန်မရှာဘဲ ပြန်သုံး
        if (scenesFor == vUri && scenes.isNotEmpty()) {
            applyMatch(scenes)
            return
        }

        matchCancelled = false
        matchRunning = true
        matchStatus = "Scene ရှာနေသည်..."
        refresh()
        val startTime = System.currentTimeMillis()

        Thread {
            var found: List<Scene>? = null
            var err: String? = null
            try {
                found = SceneDetector.detect(
                    context = this,
                    uri = vUri,
                    durationMs = videoMs,
                    threshold = 0.30,
                    isCancelled = { matchCancelled },
                    onProgress = { p ->
                        runOnUiThread {
                            matchStatus = "Scene ရှာနေသည်: $p%"
                            refresh()
                        }
                    }
                )
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            val f = found
            val e = err
            runOnUiThread {
                matchRunning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val secs = (System.currentTimeMillis() - startTime) / 1000
                when {
                    e != null -> { matchStatus = "Error: $e"; refresh() }
                    matchCancelled -> { matchStatus = "Cancel လုပ်ပြီးပါပြီ"; refresh() }
                    f.isNullOrEmpty() -> { matchStatus = "Scene မတွေ့ပါ"; refresh() }
                    else -> {
                        scenes = f
                        scenesFor = vUri
                        applyMatch(f, "(Scene ရှာချိန် ${secs}s)")
                    }
                }
            }
        }.start()
    }

    private fun applyMatch(list: List<Scene>, extra: String = "") {
        val r = SceneMatcher.match(cues, list, mp3Ms, videoMs)
        if (r.groups.isEmpty()) {
            matchStatus = r.warnings.joinToString("\n")
            refresh()
            return
        }
        rangesInput.setText(
            r.groups.joinToString("\n") { "${sec(it.clip.startMs)}-${sec(it.clip.endMs)}" }
        )
        val sb = StringBuilder()
        sb.append("✓ Scene ${list.size} ခုထဲက Segment ${r.groups.size} ခု ရွေးပြီး $extra\n")
        sb.append("Clip စုစုပေါင်း: ${r.totalMs / 1000}s၊ MP3: ${mp3Ms / 1000}s\n")
        sb.append("ကျော်ထားသော Scene: ${r.skippedScenes} ခု\n")
        if (r.warnings.isEmpty()) {
            sb.append("သတိပေးချက် မရှိပါ")
        } else {
            sb.append("⚠ သတိပေးချက် ${r.warnings.size} ခု\n")
            r.warnings.take(10).forEach { sb.append("• $it\n") }
            if (r.warnings.size > 10) sb.append("... နောက်ထပ် ${r.warnings.size - 10} ခု")
        }
        matchStatus = sb.toString()
        refresh()
    }

    private fun sec(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)

    // ---------- Segment များပေါင်း + MP3 + စာတန်း ----------

    private fun parseRanges(text: String): Pair<List<TimeRange>, String?> {
        val result = mutableListOf<TimeRange>()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for ((i, line) in lines.withIndex()) {
            val parts = line.split(Regex("\\s*[-–,]\\s*|\\s+"))
            val s = parts.getOrNull(0)?.toDoubleOrNull()
            val e = parts.getOrNull(1)?.toDoubleOrNull()
            if (parts.size != 2 || s == null || e == null) {
                return Pair(emptyList(), "စာကြောင်း ${i + 1} ပုံစံမှား: \"$line\" (ဥပမာ 5-12)")
            }
            if (s < 0 || e <= s) {
                return Pair(emptyList(), "စာကြောင်း ${i + 1}: End က Start ထက်ကြီးရမယ်")
            }
            val startMs = (s * 1000).toLong()
            val endMs = (e * 1000).toLong()
            if (videoMs > 0L && endMs > videoMs + 50) {
                return Pair(emptyList(), "စာကြောင်း ${i + 1}: Video ကြာချိန်ထက် ကျော်နေသည်")
            }
            result.add(TimeRange(startMs, min(endMs, if (videoMs > 0L) videoMs else endMs)))
        }
        if (result.isEmpty()) return Pair(emptyList(), "Segment စာရင်း ဗလာဖြစ်နေသည်")
        return Pair(result, null)
    }

    private fun buildBurnIn(vUri: Uri): BurnIn? {
        if (!burnCheck.isChecked) return null
        if (cues.isEmpty()) { toast("Burn-in အတွက် SRT အရင်ထည့်ပါ"); return null }
        val size = VideoSize.read(this, vUri)
        if (size == null) { toast("Video အရွယ်အစား ဖတ်မရပါ"); return null }
        val w = min(size.first, 1280)
        val h = (w.toLong() * size.second / size.first).toInt().coerceAtLeast(2)
        return BurnIn(cues, w, h)
    }

    private fun doRecap() {
        val vUri = videoUri
        val aUri = mp3Uri
        if (vUri == null) { toast("Video အရင်ရွေးပါ"); return }
        if (aUri == null || mp3Ms <= 0L) { toast("MP3 အရင်ရွေးပါ"); return }
        if (exporter.isRunning) { toast("Segment Export လုပ်နေဆဲ"); return }
        if (matchRunning) { toast("Scene ရှာနေဆဲ၊ ခဏစောင့်ပါ"); return }

        if (cues.isEmpty() && srtInput.text.isNotBlank()) loadSrtText()

        val (ranges, error) = parseRanges(rangesInput.text.toString())
        if (error != null) { toast(error); return }

        val burnIn = buildBurnIn(vUri)
        if (burnCheck.isChecked && burnIn == null) return

        val totalMs = ranges.sumOf { it.durationMs }
        val warn = when {
            totalMs + 1000 < mp3Ms ->
                "\n⚠ Video စုစုပေါင်း (${totalMs / 1000}s) က MP3 (${mp3Ms / 1000}s) ထက်တိုနေသည်၊ အသံအဆုံးပိုင်း ပါချင်မှ ပါမယ်"
            totalMs > mp3Ms + 1000 ->
                "\n⚠ Video စုစုပေါင်း (${totalMs / 1000}s) က MP3 (${mp3Ms / 1000}s) ထက်ရှည်နေသည်၊ အဆုံးပိုင်း အသံမရှိမယ်"
            else -> ""
        }
        val burnNote = if (burnIn != null) "\n[စာတန်း Burn-in ပါမည်၊ Re-encode လုပ်ရသဖြင့် ပိုကြာမည်]" else ""

        val out = File(cacheDir, "recap.mp4")
        recapFile = null
        recapStatus = "စတင်နေသည်... Segment ${ranges.size} ခု၊ ${totalMs / 1000} စက္ကန့်$warn$burnNote"
        refresh()

        recapExporter.export(
            videoUri = vUri,
            ranges = ranges,
            audioUri = aUri,
            audioMs = mp3Ms,
            output = out,
            burnIn = burnIn,
            onProgress = { p ->
                recapStatus = "Export လုပ်နေသည်: $p%$warn$burnNote"
                refresh()
            },
            onFinished = { ok, msg ->
                recapStatus = msg + warn + burnNote
                if (ok) recapFile = out
                refresh()
            }
        )
    }

    private fun doSaveRecap() {
        val f = recapFile
        if (f == null || !f.exists()) { toast("ပေါင်းပြီးဖိုင် မရှိသေးပါ"); return }
        savingRecap = true
        saveMp4.launch("recap.mp4")
    }

    private fun doCancel() {
        if (!exporter.isRunning && !recapExporter.isRunning && !matchRunning) {
            toast("လုပ်နေတာ မရှိပါ"); return
        }
        if (matchRunning) { matchCancelled = true; matchStatus = "ရပ်နေသည်..." }
        if (exporter.isRunning) { exporter.cancel(); exportStatus = "Cancel လုပ်ပြီးပါပြီ" }
        if (recapExporter.isRunning) { recapExporter.cancel(); recapStatus = "Cancel လုပ်ပြီးပါပြီ" }
        refresh()
    }

    private fun copyFileTo(f: File?, uri: Uri) {
        if (f == null) return
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            }
            toast("MP4 သိမ်းပြီးပါပြီ")
        } catch (e: Exception) {
            toast("သိမ်းရာတွင် Error: ${e.message}")
        }
    }

    // ---------- မျက်နှာပြင် ပြခြင်း ----------

    private fun refresh() {
        val sb = StringBuilder()
        sb.append("SRT: $srtName\n")
        sb.append("MP3: $mp3Name\n")
        sb.append("Video: $videoName\n\n")

        if (cues.isNotEmpty()) {
            val srtMs = cues.maxOf { it.endMs }
            sb.append("Cue အရေအတွက်: ${cues.size}\n")
            sb.append("SRT ကြာချိန်: ${MediaInfo.formatDuration(srtMs)} ($srtMs ms)\n")
            if (mp3Ms > 0L) {
                sb.append("MP3 ကြာချိန်: ${MediaInfo.formatDuration(mp3Ms)} ($mp3Ms ms)\n")
                val diff = srtMs - mp3Ms
                val pct = abs(diff) * 100.0 / mp3Ms
          
