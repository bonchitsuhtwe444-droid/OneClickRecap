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

/** offsetMs = ထို Segment စတဲ့ Output Timeline အချိန် */
@androidx.annotation.OptIn(UnstableApi::class)
class SubtitleBitmapOverlay(
    cues: List<SrtCue>,
    private val width: Int,
    private val height: Int,
    private val offsetMs: Long
) : BitmapOverlay() {

    private val sorted = cues.sortedBy { it.startMs }
    private var currentIdx = -2
    private val empty: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private var currentBitmap: Bitmap = empty

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val ms = presentationTimeUs / 1000 + offsetMs
        val idx = sorted.indexOfFirst { ms >= it.startMs && ms < it.endMs }
        if (idx != currentIdx) {
            currentIdx = idx
            currentBitmap = if (idx < 0) empty else render(sorted[idx].text)
        }
        return currentBitmap
    }

    private fun render(text: String): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val textSizePx = height * 0.045f
        val boxWidth = (width * 0.9f).toInt()

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
