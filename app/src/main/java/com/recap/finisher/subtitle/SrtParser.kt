package com.recap.finisher.subtitle

data class SrtCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class SrtIssue(
    val cueIndex: Int,
    val message: String
)

data class SrtResult(
    val cues: List<SrtCue>,
    val issues: List<SrtIssue>
) {
    val totalDurationMs: Long
        get() = cues.maxOfOrNull { it.endMs } ?: 0L
}

object SrtParser {

    private val TIME_REGEX = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )

    fun parse(raw: String): SrtResult {
        val cues = mutableListOf<SrtCue>()
        val issues = mutableListOf<SrtIssue>()

        // BOM ဖယ်၊ Line ending တူအောင်ပြင်
        val content = raw.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()

        if (content.isEmpty()) {
            issues.add(SrtIssue(0, "SRT ဖိုင် ဗလာဖြစ်နေသည်"))
            return SrtResult(cues, issues)
        }

        val blocks = content.split(Regex("\n{2,}"))

        for ((blockNo, block) in blocks.withIndex()) {
            val lines = block.split("\n")
            val timeLineIdx = lines.indexOfFirst { TIME_REGEX.containsMatchIn(it) }

            if (timeLineIdx == -1) {
                issues.add(SrtIssue(blockNo + 1, "Timestamp line မတွေ့ပါ"))
                continue
            }

            val m = TIME_REGEX.find(lines[timeLineIdx])!!.groupValues
            val start = toMs(m[1], m[2], m[3], m[4])
            val end = toMs(m[5], m[6], m[7], m[8])

            val index = lines.firstOrNull()?.trim()?.toIntOrNull() ?: (blockNo + 1)
            val text = lines.drop(timeLineIdx + 1).joinToString("\n").trim()

            cues.add(SrtCue(index, start, end, text))
        }

        issues.addAll(validate(cues))
        return SrtResult(cues, issues)
    }

    fun validate(cues: List<SrtCue>): List<SrtIssue> {
        val issues = mutableListOf<SrtIssue>()
        for ((i, cue) in cues.withIndex()) {
            if (cue.endMs <= cue.startMs) {
                issues.add(SrtIssue(cue.index, "End time သည် Start time ထက် မကြီးပါ"))
            }
            if (cue.text.isBlank()) {
                issues.add(SrtIssue(cue.index, "စာသား ဗလာဖြစ်နေသည်"))
            }
            if (i > 0) {
                val prev = cues[i - 1]
                if (cue.startMs < prev.endMs) {
                    issues.add(SrtIssue(cue.index, "ယခင် Cue နဲ့ အချိန်ထပ်နေသည်"))
                }
                if (cue.startMs < prev.startMs) {
                    issues.add(SrtIssue(cue.index, "အချိန်အစဉ် ပြန်ကျော့နေသည်"))
                }
            }
        }
        return issues
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long {
        // "5" ကို 500ms၊ "50" ကို 500ms၊ "500" ကို 500ms အဖြစ်ယူ
        val millis = ms.padEnd(3, '0').toLong()
        return h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 + millis
    }
}
