package com.recap.finisher

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.recap.finisher.export.SegmentExporter
import com.recap.finisher.media.MediaInfo
import com.recap.finisher.subtitle.SrtCue
import com.recap.finisher.subtitle.SrtIssue
import com.recap.finisher.subtitle.SrtParser
import com.recap.finisher.subtitle.SrtTimeTools
import java.io.File
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private var cues: List<SrtCue> = emptyList()
    private var issues: List<SrtIssue> = emptyList()
    private var srtName = "-"
    private var mp3Name = "-"
    private var videoName = "-"
    private var mp3Ms = 0L
    private var videoMs = 0L
    private var videoUri: Uri? = null

    private var exportStatus = ""
    private var segmentFile: File? = null

    private lateinit var infoView: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var exporter: SegmentExporter

    private val pickSrt =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadSrt(uri)
        }

    private val pickMp3 =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
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
            if (uri != null) copySegmentTo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exporter = SegmentExporter(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(title("One-Click Recap"))
        root.addView(button("1. SRT ဖိုင်ရွေး") { pickSrt.launch(arrayOf("*/*")) })
        root.addView(button("2. TTS MP3 ရွေး") { pickMp3.launch(arrayOf("audio/*")) })
        root.addView(button("3. Movie Video ရွေး") { pickVideo.launch(arrayOf("video/*")) })

        root.addView(title("Timing ပြင်ရန်"))
        root.addView(button("MP3 Duration နဲ့ ကိုက်အောင် Scale လုပ်") { doScale() })
        root.addView(button("အချိန်ထပ်တာ/Error ပြင်") { doFix() })
        root.addView(button("-100 ms ရွှေ့") { doShift(-100) })
        root.addView(button("+100 ms ရွှေ့") { doShift(100) })
        root.addView(button("ပြင်ပြီး SRT သိမ်း") { doSave() })

        root.addView(title("Video ဖြတ်ရန် (စမ်းသပ်)"))
        startInput = numberInput("Start (စက္ကန့်) ဥပမာ 10")
        endInput = numberInput("End (စက္ကန့်) ဥပမာ 25")
        root.addView(startInput)
        root.addView(endInput)
        root.addView(button("Segment ဖြတ်ထုတ်") { doExport() })
        root.addView(button("Export ရပ်မယ် (Cancel)") { doCancel() })
        root.addView(button("ဖြတ်ပြီးဖိုင်ကို MP4 အဖြစ်သိမ်း") { doSaveSegment() })

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
        if (exporter.isRunning) exporter.cancel()
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

    // ---------- Timing ပြင်ခြင်း ----------

    private fun requireCues(): Boolean {
        if (cues.isEmpty()) { toast("SRT အရင်ရွေးပါ"); return false }
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

    // ---------- Video Segment ဖြတ်ခြင်း ----------

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

    private fun doCancel() {
        if (!exporter.isRunning) { toast("Export မလုပ်နေပါ"); return }
        exporter.cancel()
        exportStatus = "Cancel လုပ်ပြီးပါပြီ"
        refresh()
    }

    private fun doSaveSegment() {
        val f = segmentFile
        if (f == null || !f.exists()) { toast("ဖြတ်ပြီးဖိုင် မရှိသေးပါ"); return }
        saveMp4.launch("segment.mp4")
    }

    private fun copySegmentTo(uri: Uri) {
        val f = segmentFile ?: return
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
                sb.append("ကွာခြားချက်: $diff ms (%.2f%%)\n".format(pct))
                if (pct > 1.0) {
                    sb.append("⚠ ကွာခြားမှု 1% ကျော်နေသည်၊ Scale လုပ်သင့်သည်\n")
                } else {
                    sb.append("✓ SRT နဲ့ MP3 ကြာချိန် ကိုက်ညီသည်\n")
                }
            }
        }
        if (videoMs > 0L) {
            sb.append("Video ကြာချိန်: ${MediaInfo.formatDuration(videoMs)}\n")
        }

        if (cues.isNotEmpty()) {
            sb.append("\nအမှား: ${issues.size} ခု\n")
            issues.take(15).forEach { sb.append("• Cue ${it.cueIndex}: ${it.message}\n") }
            if (issues.size > 15) sb.append("... နောက်ထပ် ${issues.size - 15} ခု\n")
        }

        if (exportStatus.isNotEmpty()) {
            sb.append("\n[Segment Export] $exportStatus\n")
        }
        infoView.text = sb.toString()
    }

    // ---------- UI Helper ----------

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun numberInput(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun title(label: String) = TextView(this).apply {
        text = label
        textSize = 20f
        setPadding(0, dp(12), 0, dp(4))
        visibility = View.VISIBLE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
