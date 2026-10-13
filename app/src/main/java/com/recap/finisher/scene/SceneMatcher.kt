package com.recap.finisher.scene

import com.recap.finisher.export.TimeRange
import com.recap.finisher.subtitle.SrtCue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class MatchGroup(
    val narrStartMs: Long,
    val narrEndMs: Long,
    val cueCount: Int,
    val clip: TimeRange,
    val firstScene: Int,
    val lastScene: Int
)

data class MatchResult(
    val groups: List<MatchGroup>,
    val warnings: List<String>,
    val skippedScenes: Int
) {
    val totalMs: Long get() = groups.sumOf { it.clip.durationMs }
}

object SceneMatcher {

    private fun fail(msg: String) = MatchResult(emptyList(), listOf(msg), 0)

    private fun fmt(ms: Long): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        return "%d:%02d".format(m, s)
    }

    private fun sceneIdxAt(sc: List<Scene>, t: Long): Int =
        sc.indexOfLast { it.startMs <= t }.coerceAtLeast(0)

    /**
     * cues: Narration (TTS) Timeline၊ scenes: Movie Timeline၊
     * narrationMs: MP3 ကြာချိန်၊ movieMs: Video ကြာချိန်။
     */
    fun match(
        cues: List<SrtCue>,
        scenes: List<Scene>,
        narrationMs: Long,
        movieMs: Long,
        minGroupMs: Long = 3000L,
        minTailMs: Long = 1000L
    ): MatchResult {
        if (cues.isEmpty()) return fail("SRT Cue မရှိပါ")
        if (scenes.isEmpty()) return fail("Scene စာရင်း ဗလာဖြစ်နေသည်")
        if (narrationMs <= 0L) return fail("MP3 ကြာချိန် မသိပါ")
        if (movieMs <= 0L) return fail("Video ကြာချိန် မသိပါ")
        if (movieMs < narrationMs) {
            return fail(
                "Video (${fmt(movieMs)}) က MP3 (${fmt(narrationMs)}) ထက်တိုနေသည်။ " +
                    "Scene ထပ်မသုံးဘဲ မလောက်ပါ၊ ပိုရှည်တဲ့ Video သုံးပါ"
            )
        }

        val warnings = mutableListOf<String>()
        val sc = scenes.sortedBy { it.startMs }
        val sortedCues = cues.sortedBy { it.startMs }

        val lastCueEnd = sortedCues.maxOf { it.endMs }
        if (lastCueEnd > narrationMs + 1000L) {
            warnings.add(
                "SRT (${fmt(lastCueEnd)}) က MP3 (${fmt(narrationMs)}) ထက်ရှည်နေသည်၊ Scale အရင်လုပ်ပါ"
            )
        }

        // ---- Cue တွေကို အုပ်စုဖွဲ့ (အုပ်စုတစ်ခု အနည်းဆုံး minGroupMs) ----
        val groupEnds = mutableListOf<Long>()
        val groupCounts = mutableListOf<Int>()
        var gStart = 0L
        var count = 0
        for ((i, c) in sortedCues.withIndex()) {
            count++
            val end = min(c.endMs, narrationMs)
            val isLast = i == sortedCues.lastIndex
            if (isLast) {
                if (groupEnds.isNotEmpty() && narrationMs - gStart < minTailMs) {
                    groupEnds[groupEnds.lastIndex] = narrationMs
                    groupCounts[groupCounts.lastIndex] = groupCounts.last() + count
                } else {
                    groupEnds.add(narrationMs)
                    groupCounts.add(count)
                }
            } else if (end - gStart >= minGroupMs) {
                groupEnds.add(end)
                groupCounts.add(count)
                gStart = end
                count = 0
            }
        }

        // ---- အုပ်စုတစ်ခုချင်းစီအတွက် Movie Clip ရွေး ----
        val groups = mutableListOf<MatchGroup>()
        val touched = BooleanArray(sc.size)
        var cursor = 0L
        var prevEnd = 0L

        for (gi in groupEnds.indices) {
            val nStart = prevEnd
            val nEnd = groupEnds[gi]
            prevEnd = nEnd
            val d = nEnd - nStart
            if (d <= 0L) continue

            // Narration အနေအထားကို Movie အချိန်နဲ့ အချိုးချ
            val target = (nStart.toDouble() / narrationMs * movieMs).toLong()
            // ကျန်တဲ့ Narration အတွက် Footage လောက်အောင် ချန်ထား
            val latest = movieMs - (narrationMs - nStart)

            var begin = max(cursor, sc[sceneIdxAt(sc, target)].startMs)
            begin = max(cursor, min(begin, latest))

            // Scene အဆုံးနားက အတိတ်ပိုင်းအတိုလေးကနေ မစအောင် နောက် Scene အစကို ရွှေ့
            val ci = sceneIdxAt(sc, begin)
            val cs = sc[ci]
            if (begin > cs.startMs && cs.endMs - begin < minTailMs &&
                ci + 1 < sc.size && sc[ci + 1].startMs <= latest
            ) {
                begin = sc[ci + 1].startMs
            }

            var end = begin + d
            if (end > movieMs) {
                end = movieMs
                warnings.add("အုပ်စု ${gi + 1}: Footage မလောက်၍ Clip တိုသွားသည်")
            }
            if (end <= begin) {
                warnings.add("အုပ်စု ${gi + 1}: ရွေးစရာ Footage မကျန်တော့ပါ")
                continue
            }

            val a = sceneIdxAt(sc, begin)
            val b = sceneIdxAt(sc, end - 1)
            for (k in a..b) touched[k] = true

            if (b - a >= 3) {
                warnings.add(
                    "အုပ်စု ${gi + 1} (Narration ${fmt(nStart)}): Scene ${a + 1}-${b + 1} ကို ဖြတ်ကျော်ထားသည်"
                )
            }
            if (abs(begin - target) > movieMs * 0.25) {
                warnings.add(
                    "အုပ်စု ${gi + 1}: Movie အနေအထားက မျှော်မှန်းချက်နဲ့ ${abs(begin - target) / 1000} စက္ကန့် ကွာသည်"
                )
            }

            groups.add(
                MatchGroup(nStart, nEnd, groupCounts[gi], TimeRange(begin, end), a + 1, b + 1)
            )
            cursor = end
        }

        // ---- ထပ်နေသလား နောက်ဆုံးစစ် ----
        for (i in 1 until groups.size) {
            if (groups[i].clip.startMs < groups[i - 1].clip.endMs) {
                warnings.add("အုပ်စု ${i + 1}: ယခင် Clip နဲ့ ထပ်နေသည် (Scene ထပ်သုံးမိ)")
            }
        }

        val skipped = touched.count { !it }
        return MatchResult(groups, warnings, skipped)
    }
}
