package com.recap.finisher.scene

import android.graphics.Bitmap
import android.util.Base64
import com.recap.finisher.subtitle.SrtCue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object GeminiAnchors {

    /** Background thread မှ ခေါ်ရမည်။ Error ဆို Exception ပစ်မည်။ */
    fun request(
        apiKey: String,
        model: String,
        cues: List<SrtCue>,
        scenes: List<Scene>,
        narrationMs: Long,
        movieMs: Long
    ): List<Anchor> {
        val parts = JSONArray()

        val script = cues.sortedBy { it.startMs }
            .joinToString("\n") { "[${it.startMs / 1000}s] ${it.text.replace("\n", " ")}" }
        val prompt = "This is a movie recap. The narration script (Burmese, with time in seconds) is below. " +
            "After it come thumbnails of movie scenes, each labelled with its start time in the movie. " +
            "Pick 6 to 10 key story events. For each give narrSec (when the narration describes it) " +
            "and movieSec (the movie time where that event is shown, choose from the scene start times). " +
            "movieSec must never decrease as narrSec increases. Narration length ${narrationMs / 1000}s, " +
            "movie length ${movieMs / 1000}s. Reply ONLY with a JSON array like " +
            "[{\"narrSec\":12.0,\"movieSec\":30.0}].\n\nSCRIPT:\n$script\n\nSCENES:"
        parts.put(JSONObject().put("text", prompt))

        for (s in scenes) {
            val th = s.thumb ?: continue
            parts.put(JSONObject().put("text", "Scene ${s.index} starts at ${s.startMs / 1000.0}s"))
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", toBase64(th))
                )
            )
        }

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("HTTP $code: ${text.take(300)}")

            val out = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            val arr = JSONArray(out.replace("```json", "").replace("```", "").trim())
            val list = mutableListOf<Anchor>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Anchor((o.getDouble("narrSec") * 1000).toLong(), (o.getDouble("movieSec") * 1000).toLong()))
            }
            return list
        } finally {
            conn.disconnect()
        }
    }

    private fun toBase64(b: Bitmap): String {
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }
}
