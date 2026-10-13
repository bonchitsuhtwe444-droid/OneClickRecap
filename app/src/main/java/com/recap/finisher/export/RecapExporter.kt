package com.recap.finisher.export

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

data class TimeRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

@androidx.annotation.OptIn(UnstableApi::class)
class RecapExporter(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()
    private var transformer: Transformer? = null
    private var running = false

    val isRunning: Boolean get() = running

    /**
     * ranges ကို အစဉ်လိုက်ဖြတ်ပေါင်း၊ မူရင်းအသံဖယ်၊ audioUri ကို အသံအဖြစ်တင်။
     * Main thread မှ ခေါ်ရမည်။
     */
    fun export(
        videoUri: Uri,
        ranges: List<TimeRange>,
        audioUri: Uri,
        audioMs: Long,
        output: File,
        onProgress: (Int) -> Unit,
        onFinished: (success: Boolean, message: String) -> Unit
    ) {
        if (running) {
            onFinished(false, "Export လုပ်နေဆဲဖြစ်သည်")
            return
        }
        if (ranges.isEmpty()) {
            onFinished(false, "Segment စာရင်း ဗလာဖြစ်နေသည်")
            return
        }

        if (output.exists()) output.delete()

        val totalVideoMs = ranges.sumOf { it.durationMs }

        // Video sequence: Segment တစ်ခုချင်းစီ၊ မူရင်းအသံဖယ်
        val videoItems = ranges.map { r ->
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(r.startMs)
                .setEndPositionMs(r.endMs)
                .build()
            val item = MediaItem.Builder()
                .setUri(videoUri)
                .setClippingConfiguration(clip)
                .build()
            EditedMediaItem.Builder(item)
                .setRemoveAudio(true)
                .build()
        }

        // Audio sequence: MP3 ကို Video စုစုပေါင်းကြာချိန်ထက် မရှည်စေရ
        val audioItemBuilder = MediaItem.Builder().setUri(audioUri)
        if (audioMs > totalVideoMs) {
            audioItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(0)
                    .setEndPositionMs(totalVideoMs)
                    .build()
            )
        }
        val audioItem = EditedMediaItem.Builder(audioItemBuilder.build())
            .setRemoveVideo(true)
            .build()

        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence(videoItems),
                EditedMediaItemSequence(listOf(audioItem))
            )
        ).build()

        val t = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopPolling()
                    onProgress(100)
                    onFinished(true, "ပြီးပါပြီ: ${output.length() / 1024} KB")
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    stopPolling()
                    onFinished(
                        false,
                        "Error: ${exportException.errorCodeName} - ${exportException.message}"
                    )
                }
            })
            .build()

        transformer = t
        running = true

        try {
            t.start(composition, output.absolutePath)
        } catch (e: Exception) {
            running = false
            onFinished(false, "စတင်မရပါ: ${e.message}")
            return
        }

        startPolling(t, onProgress)
    }

    fun cancel() {
        transformer?.cancel()
        stopPolling()
    }

    private fun startPolling(t: Transformer, onProgress: (Int) -> Unit) {
        val poll = object : Runnable {
            override fun run() {
                if (!running) return
                val state = t.getProgress(progressHolder)
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    onProgress(progressHolder.progress)
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(poll)
    }

    private fun stopPolling() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }
}
