package com.recap.finisher.subtitle

object SrtTimeTools {

    /** Cue အားလုံးကို offsetMs ဖြင့် ရွှေ့ (အနုတ်လည်းရ၊ 0 အောက်မကျစေ) */
    fun shift(cues: List<SrtCue>, offsetMs: Long): List<SrtCue> =
        cues.map {
            it.copy(
                startMs = (it.startMs + offsetMs).coerceAtLeast(0L),
                endMs = (it.endMs + offsetMs).coerceAtLeast(0L)
            )
        }

    /** နောက်ဆုံး Cue ပြီးချိန်ကို targetMs နဲ့ ကိုက်အောင် အချိုးကျ ဆွဲဆန့်/ချုံ့ */
    fun scaleToDuration(cues: List<SrtCue>, targetMs: Long): List<SrtCue> {
        val current = cues.maxOfOrNull { it.endMs } ?: return cues
        if (current <= 0L || targetMs <= 0L) return cues
        val factor = targetMs.toDouble() / current.toDouble()
        return cues.map {
            it.copy(
                startMs = (it.startMs * factor).toLong(),
                endMs = (it.endMs * factor).toLong()
            )
        }
    }

    /** အချိန်ထပ်နေတာ၊ End ≤ Start ဖြစ်နေတာတွေကို ပြင် */
    fun fixOverlaps(cues: List<SrtCue>, minDurationMs: Long = 500L): List<SrtCue> {
        val sorted = cues.sortedBy { it.startMs }
        val result = mutableListOf<SrtCue>()
        for (cue in sorted) {
            var start = cue.startMs
            var end = cue.endMs
            if (result.isNotEmpty()) {
                val prevEnd = result.last().endMs
                if (start < prevEnd) start = prevEnd
            }
            if (end <= start) end = start + minDurationMs
            result.add(cue.copy(startMs = start, endMs = end))
        }
        // Index ကို 1 ကစပြီး ပြန်စီ
        return result.mapIndexed { i, c -> c.copy(index = i + 1) }
    }

    fun formatTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1_000
        val milli = ms % 1_000
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }

    /** Cue စာရင်းကို SRT စာသားအဖြစ် ပြန်ထုတ် */
    fun toSrt(cues: List<SrtCue>): String =
        cues.mapIndexed { i, c ->
            "${i + 1}\n${formatTime(c.startMs)} --> ${formatTime(c.endMs)}\n${c.text}\n"
        }.joinToString("\n")
}
