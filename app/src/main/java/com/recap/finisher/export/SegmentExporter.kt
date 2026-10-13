package com.recap.finisher.export

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
class SegmentExporter(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()
    private var transformer: Transformer? = null
    private var running = false

    val isRunning: Boolean get() = running

    /**
     * videoUri ထဲက startMs ကနေ endMs အထိ ဖြတ်ပြီး output ဖိုင်ကို ထုတ်သည်။
     * Main thread မှ ခေါ်ရမည်။
     */
    fun export(
        videoUri: Uri,
        startMs: Long,
        endMs: Long,
        output: File,
        onProgress: (Int) -> Unit,
        onFinished: (success: Boolean, message: String) -> Unit
    ) {
        if (running) {
            onFinished(false, "Export လုပ်နေဆဲဖြစ်သည်")
            return
        }
        if (endMs <= startMs) {
            onFinished(false, "End အချိန်သည် Start ထက် ကြီးရမည်")
            return
        }

        if (output.exists()) output.delete()

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(videoUri)
            .setClippingConfiguration(clipping)
            .build()

        val edited = EditedMediaItem.Builder(mediaItem).build()

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
                    onFinished(false, "Error: ${exportException.errorCodeName} - ${exportException.message}")
                }
            })
            .build()

        transformer = t
        running = true

        try {
            t.start(edited, output.absolutePath)
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
