package com.mica.music.data.local

import com.mica.music.data.LyricCue
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.CURRENT_LYRICS_DOCUMENT_VERSION
import com.mica.music.data.toLyricsDocumentCompat
import org.json.JSONArray
import org.json.JSONObject

internal object LyricsDocumentCodec {
    fun encode(document: LyricsDocument): String = JSONObject()
        .put("version", CURRENT_LYRICS_DOCUMENT_VERSION)
        .put("format", document.format.name)
        .put("origin", document.origin.name)
        // Kept temporarily so older app versions can still read the same line payload.
        .put("source", legacySource(document))
        .put("lines", JSONArray().apply {
            document.lines.forEach { line ->
                put(JSONObject()
                    .put("id", line.id)
                    .put("startMs", line.startMs)
                    .put("parts", JSONArray().apply {
                        line.parts.forEach { part ->
                            put(JSONObject().put("role", part.role.name).put("text", part.text))
                        }
                    })
                    .put("tokens", JSONArray().apply {
                        line.tokens.forEach { token ->
                            put(JSONObject()
                                .put("text", token.text)
                                .put("startMs", token.startMs)
                                .put("partRole", token.partRole.name)
                                .apply { token.endMs?.let { put("endMs", it) } })
                        }
                    })
                    .apply { line.endMs?.let { put("endMs", it) } })
            }
        })
        .toString()

    fun decode(json: String): LyricsDocument {
        if (json.isBlank() || json == "[]") return LyricsDocument()
        return runCatching {
            if (json.trimStart().startsWith("[")) {
                decodeLegacyLines(JSONArray(json)).toLyricsDocumentCompat()
            } else {
                decodeObject(JSONObject(json))
            }
        }.getOrDefault(LyricsDocument())
    }

    fun canonicalFingerprint(json: String): String = encode(decode(json))

    private fun decodeObject(json: JSONObject): LyricsDocument {
        val legacy = json.optString("source")
        val format = enumValueOrNull<LyricsFormat>(json.optString("format"))
            ?: legacyFormat(legacy)
        val origin = enumValueOrNull<LyricsOrigin>(json.optString("origin"))
            ?: legacyOrigin(legacy)
        val lines = json.optJSONArray("lines") ?: return LyricsDocument(format = format, origin = origin)
        return LyricsDocument(
            version = CURRENT_LYRICS_DOCUMENT_VERSION,
            format = format,
            origin = origin,
            lines = buildList(lines.length()) {
                for (index in 0 until lines.length()) {
                    val line = lines.optJSONObject(index) ?: continue
                    if (!line.has("startMs")) continue
                    val parts = decodeParts(line.optJSONArray("parts"))
                    if (parts.isEmpty()) continue
                    add(LyricLineNode(
                        id = line.optString("id").ifBlank { "$index-${line.getInt("startMs")}" },
                        startMs = line.getInt("startMs"),
                        endMs = line.optInt("endMs").takeIf { line.has("endMs") },
                        parts = parts,
                        tokens = decodeTokens(line.optJSONArray("tokens")),
                    ))
                }
            },
        )
    }

    private fun decodeParts(values: JSONArray?): List<LyricTextPart> {
        if (values == null) return emptyList()
        return buildList(values.length()) {
            for (index in 0 until values.length()) {
                val part = values.optJSONObject(index) ?: continue
                if (!part.has("text")) continue
                add(LyricTextPart(
                    role = enumValueOrNull<LyricTextRole>(part.optString("role")) ?: LyricTextRole.EXTRA,
                    text = part.getString("text"),
                ))
            }
        }
    }

    private fun decodeTokens(values: JSONArray?): List<LyricToken> {
        if (values == null) return emptyList()
        return buildList(values.length()) {
            for (index in 0 until values.length()) {
                val token = values.optJSONObject(index) ?: continue
                if (!token.has("text") || !token.has("startMs")) continue
                add(LyricToken(
                    text = token.getString("text"),
                    startMs = token.getInt("startMs"),
                    endMs = token.optInt("endMs").takeIf { token.has("endMs") },
                    partRole = enumValueOrNull<LyricTextRole>(token.optString("partRole"))
                        ?: LyricTextRole.ORIGINAL,
                ))
            }
        }
    }

    private fun decodeLegacyLines(array: JSONArray): List<LyricLine> = buildList(array.length()) {
        for (index in 0 until array.length()) {
            val value = array.optJSONObject(index) ?: continue
            if (!value.has("t") || !value.has("x")) continue
            val cues = value.optJSONArray("c")?.let { cueArray ->
                buildList(cueArray.length()) {
                    for (cueIndex in 0 until cueArray.length()) {
                        val cue = cueArray.optJSONObject(cueIndex) ?: continue
                        if (cue.has("t") && cue.has("x")) {
                            add(LyricCue(cue.getInt("t"), cue.getString("x")))
                        }
                    }
                }
            }.orEmpty()
            add(LyricLine(
                timeMs = value.getInt("t"),
                text = value.getString("x"),
                cues = cues,
                endTimeMs = value.optInt("e").takeIf { value.has("e") },
            ))
        }
    }

    private fun legacySource(document: LyricsDocument): String = when {
        document.format == LyricsFormat.LRC -> "LRC"
        document.format == LyricsFormat.TTML -> "TTML"
        document.origin == LyricsOrigin.EMBEDDED -> "EMBEDDED"
        document.origin == LyricsOrigin.EXTERNAL -> "EXTERNAL"
        else -> "COMPATIBILITY"
    }

    private fun legacyFormat(source: String): LyricsFormat = when (source) {
        "LRC" -> LyricsFormat.LRC
        "TTML" -> LyricsFormat.TTML
        else -> LyricsFormat.UNKNOWN
    }

    private fun legacyOrigin(source: String): LyricsOrigin = when (source) {
        "EMBEDDED" -> LyricsOrigin.EMBEDDED
        "EXTERNAL" -> LyricsOrigin.EXTERNAL
        else -> LyricsOrigin.UNKNOWN
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }
}
