package com.recap.finisher.scene

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlin.math.abs
import kotlin.math.min

data class Scene(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val thumb: Bitmap?
) {
    val durationMs: Long get() = endMs - startMs
}

object SceneDetector {

    /**
     * Background thread မှ ခေါ်ရမည်။
     * threshold: 0.0-1.0 (နည်းလေ Scene များလေ)၊ stepMs: Frame ယူသည့်အကွာ၊
     * minSceneMs: ဒီထက်တိုတဲ့ Scene မဖြစ်စေ။
     */
    fun detect(
        context: Context,
        uri: Uri,
        durationMs: Long,
        threshold: Double,
        stepMs: Long = 500L,
        minSceneMs: Long = 1500L,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): List<Scene> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val boundaries = mutableListOf<Long>()   // Scene စတဲ့အချိန်များ
            boundaries.add(0L)
            var prev: FloatArray? = null
            var lastStart = 0L
            var t = 0L

            while (t < durationMs) {
                if (isCancelled()) return emptyList()

                val bmp = frame(retriever, t * 1000, 64, 36)
                if (bmp != null) {
                    val hist = histogram(bmp)
                    bmp.recycle()
                    val p = prev
                    if (p != null && t - lastStart >= minSceneMs && diff(p, hist) > threshold) {
                        boundaries.add(t)
                        lastStart = t
                    }
                    prev = hist
                }
                onProgress((t * 100 / durationMs).toInt())
                t += stepMs
            }

            // Scene စာရင်း + Thumbnail
            val scenes = mutableListOf<Scene>()
            for (i in boundaries.indices) {
                if (isCancelled()) return emptyList()
                val s = boundaries[i]
                val e = if (i + 1 < boundaries.size) boundaries[i + 1] else durationMs
                val thumbAt = s + min(500L, (e - s) / 2)
                val thumb = frame(retriever, thumbAt * 1000, 160, 90)
                scenes.add(Scene(i + 1, s, e, thumb))
            }
            onProgress(100)
            return scenes
        } finally {
            retriever.release()
        }
    }

    private fun frame(r: MediaMetadataRetriever, tUs: Long, w: Int, h: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
        } else {
            r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?.let { Bitmap.createScaledBitmap(it, w, h, true) }
        }

    /** RGB 4x4x4 = 64 bin Histogram (ပေါင်းလဒ် 1) */
    private fun histogram(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val hist = FloatArray(64)
        for (p in px) {
            val r = ((p shr 16) and 0xFF) shr 6
            val g = ((p shr 8) and 0xFF) shr 6
            val b = (p and 0xFF) shr 6
            hist[(r shl 4) or (g shl 2) or b] += 1f
        }
        for (i in hist.indices) hist[i] /= px.size.toFloat()
        return hist
    }

    /** 0.0 (တူ) ကနေ 1.0 (လုံးဝကွာ) */
    private fun diff(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / 2.0
    }
}
