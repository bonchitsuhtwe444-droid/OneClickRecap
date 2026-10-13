package com.recap.finisher.subtitle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay

object VideoSize {
    fun read(context: Context, uri: Uri): Pair<Int, Int>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w == null || h == null || w <= 0 || h <= 0) null
            else if (rot == 90 || rot == 270) Pair(h, w) else Pair(w, h)
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }
}

/**
 * Segment တစ်ခုချင်းစီအတွက် Overlay။ offsetMs = ထို Segment စတဲ့ Output Timeline အချိန်။
 * ဘယ်ဘက်အပေါ်မှာ အဝါရောင် Debug စာ ပါမည် (Output အချိန်၊ Cue နံပါတ်)။
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SubtitleBitmapOverlay(
    cues: List<SrtCue>,
    private val width: Int,
    private val height: Int,
    private val offsetMs: Long
) : BitmapOverlay() {

    private val sorted = cues.sortedBy { it.startMs }
    private var currentKey = -1L
    private var currentBitmap: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val ms = presentationTimeUs / 1000 + offsetMs
        val idx = sorted.indexOfFirst { ms >= it.startMs && ms < it.endMs }
        val key = (ms / 500) * 1000 + (idx + 1)
        if (key != currentKey) {
            currentKey = key
            currentBitmap = render(if (idx < 0) null else sorted[idx].text, ms, idx)
        }
        return currentBitmap
    }

    private fun render(text: String?, ms: Long, idx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val textSizePx = height * 0.045f
        val boxWidth = (width * 0.9f).toInt()

        val dbg = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = height * 0.04f }
        val dbgText = "t=%.1fs cue=%d/%d".format(ms / 1000.0, idx + 1, sorted.size)
        dbg.style = Paint.Style.STROKE
        dbg.strokeWidth = 4f
        dbg.color = Color.BLACK
        canvas.drawText(dbgText, 20f, height * 0.08f, dbg)
        dbg.style = Paint.Style.FILL
        dbg.color = Color.YELLOW
        canvas.drawText(dbgText, 20f, height * 0.08f, dbg)

        if (text.isNullOrBlank()) return bmp

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            strokeJoin = Paint.Join.ROUND
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, boxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.25f)
            .build()

        canvas.save()
        canvas.translate((width - boxWidth) / 2f, height - layout.height - height * 0.06f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = textSizePx * 0.18f
        paint.color = Color.BLACK
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        layout.draw(canvas)
        canvas.restore()
        return bmp
    }
}
