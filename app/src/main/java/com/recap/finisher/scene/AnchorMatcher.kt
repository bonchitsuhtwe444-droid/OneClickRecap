package com.recap.finisher.scene

import com.recap.finisher.export.TimeRange
import com.recap.finisher.subtitle.SrtCue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Anchor(val narrMs: Long, val movieMs: Long)

data class AnchorResult(val ranges: List<TimeRange>, val warnings: List<String>)

object AnchorMatcher {

    private fun interpolate(a: List<Anchor>, t: Long): Long {
        for (i in 1 until a.size) {
            val p = a[i - 1]
            val q = a[i]
            if (t <= q.narrMs) {
                val span = q.narrMs - p.narrMs
                if (span <= 0L) return q.movieMs
                val f = (t - p.narrMs).toDouble() / span
                return p.movieMs + (f * (q.movieMs - p.movieMs)).toLong()
            }
        }
        return a.last().movieMs
    }

    /** speed: 0.5 = 2 ဆနှေး (Movie ကနေ Narration ရဲ့ ထက်ဝက်ပဲ ယူ) */
    fun match(
        cues: List<SrtCue>,
        scenes: List<Scene>,
        narrationMs: Long,
        movieMs: Long,
        userAnchors: List<Anchor>,
        speed: Double = 1.0,
        minGroupMs: Long = 3000L
    ): AnchorResult {
        val warnings = mutableListOf<String>()
        if (cues.isEmpty() || scenes.isEmpty()) {
            return AnchorResult(emptyList(), listOf("SRT သို့မဟုတ် Scene မရှိပါ"))
        }
        if (movieMs < (narrationMs * speed).toLong()) {
            return AnchorResult(emptyList(), listOf("Video က လိုအပ်တာထက် တိုနေသည်"))
        }

        val all = mutableListOf(Anchor(0L, 0L))
        for (u in userAnchors.sortedBy { it.narrMs }) {
            if (u.narrMs <= 0L || u.narrMs >= narrationMs) continue
            if (u.movieMs < all.last().movieMs || u.movieMs > movieMs) {
                warnings.add("Anchor ${u.narrMs / 1000}s→${u.movieMs / 1000}s ကို ကျော်ထားသည် (အစဉ်မှား)")
                continue
            }
            all.add(u)
        }
        all.add(Anchor(narrationMs, movieMs))

        val sc = scenes.sortedBy { it.startMs }
        val sorted = cues.sortedBy { it.startMs }
        val ends = mutableListOf<Long>()
        var gStart = 0L
        for ((i, c) in sorted.withIndex()) {
            val e = min(c.endMs, narrationMs)
            if (i == sorted.lastIndex) {
                if (ends.isNotEmpty() && narrationMs - gStart < 1000L) ends[ends.lastIndex] = narrationMs
                else ends.add(narrationMs)
            } else if (e - gStart >= minGroupMs) {
                ends.add(e)
                gStart = e
            }
        }

        val ranges = mutableListOf<TimeRange>()
        var cursor = 0L
        var prev = 0L
        for ((gi, nEnd) in ends.withIndex()) {
            val nStart = prev
            prev = nEnd
            val d = nEnd - nStart
            if (d <= 0L) continue

            val srcLen = (d * speed).toLong()
            val target = interpolate(all, nStart)
            val remainSrc = ((narrationMs - nStart) * speed).toLong()
            val latest = max(movieMs - remainSrc, cursor)
            var begin = target.coerceIn(cursor, latest)

            val snap = sc.map { it.startMs }
                .filter { it in cursor..latest }
                .minByOrNull { abs(it - begin) }
            if (snap != null && abs(snap - begin) <= 2000L) begin = snap

            val end = min(begin + srcLen, movieMs)
            if (end <= begin) {
                warnings.add("အုပ်စု ${gi + 1}: Footage မကျန်တော့ပါ")
                continue
            }
            if (end - begin < srcLen) warnings.add("အုပ်စု ${gi + 1}: Footage မလောက်၍ Clip တိုသွားသည်")
            if (abs(begin - target) > 5000L) {
                warnings.add("အုပ်စု ${gi + 1}: Anchor နဲ့ ${abs(begin - target) / 1000}s ကွာသည်")
            }
            ranges.add(TimeRange(begin, end))
            cursor = end
        }
        return AnchorResult(ranges, warnings)
    }
}
