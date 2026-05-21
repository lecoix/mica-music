package com.mica.music.data.scanner



import java.nio.charset.Charset

import java.nio.charset.StandardCharsets



/**

 * 歌词字节解码。ID3 内嵌歌词优先按帧头 **encoding 字节**（ID3v2 规范）解码，

 * 仅当结果不可用或 encoding=0 且明显乱码时再回退 GB18030 / 自动探测。

 *

 * ID3 encoding 字节：0=ISO-8859-1，1=UTF-16LE，2=UTF-16BE，3=UTF-8。

 */

internal object LyricsEncoding {



    private val gb18030 = Charset.forName("GB18030")

    private val gbk = Charset.forName("GBK")

    private val utf16Be = Charset.forName("UTF-16BE")



    private val hanLatinHan = Regex("""[\u4e00-\u9fff][a-zA-Z][\u4e00-\u9fff]""")

    private val lrcTimestamp = Regex("""\[\d{1,2}:\d{2}""")



    fun stripBomAndControls(text: String): String {

        var s = text.trim('\u0000')

        while (s.isNotEmpty() && (s.first() == '\uFEFF' || s.first() == '\uFFFE')) {

            s = s.drop(1).trimStart()

        }

        return s.replace("\uFFFD", "").trim()

    }



    private fun stripBomOnly(text: String): String {

        var s = text.trim('\u0000')

        while (s.isNotEmpty() && (s.first() == '\uFEFF' || s.first() == '\uFFFE')) {

            s = s.drop(1).trimStart()

        }

        return s.trim()

    }



    /**

     * ID3 USLT 等帧正文：严格按 [id3Encoding] 选字符集；失败时再有限回退。

     */

    fun decodeId3Bytes(bytes: ByteArray, id3Encoding: Int): String {

        if (bytes.isEmpty()) return ""

        decodeWithId3Encoding(bytes, id3Encoding)?.let { return it }



        // 常见误标：encoding=0 (Latin-1) 实际是 GBK

        if (id3Encoding == 0) {

            decodeWithCharset(bytes, gb18030)?.let { return it }

            decodeWithCharset(bytes, gbk)?.let { return it }

        }



        // 非法/未知 encoding 字节时才做通用探测（外挂 lrc 等仍走 decodeBytes）

        if (id3Encoding !in 0..3) {

            return decodeBytes(bytes)

        }

        return ""

    }



    /** 非 ID3 路径：外挂 lrc、FLAC 注释、原始字节扫描等。 */

    fun decodeBytes(bytes: ByteArray): String =

        pickBestText(bytes, charsetsForBytes(bytes))



    private fun charsetForId3Encoding(encoding: Int): Charset? = when (encoding) {

        0 -> StandardCharsets.ISO_8859_1

        1 -> StandardCharsets.UTF_16LE

        2 -> utf16Be

        3 -> StandardCharsets.UTF_8

        else -> null

    }



    private fun decodeWithId3Encoding(slice: ByteArray, encoding: Int): String? {

        val charset = charsetForId3Encoding(encoding) ?: return null

        val raw = runCatching { stripBomOnly(String(slice, charset)) }.getOrNull() ?: return null

        return raw.takeIf { isAcceptableId3LyricsText(it, encoding) }

    }



    private fun decodeWithCharset(slice: ByteArray, charset: Charset): String? {

        val raw = runCatching { stripBomOnly(String(slice, charset)) }.getOrNull() ?: return null

        return raw.takeIf { isAcceptableId3LyricsText(it, encoding = 0) }

    }



    /** encoding 为 1–3 时信任 ID3 标记；为 0 时做乱码检测（含 GBK 回退）。 */

    private fun isAcceptableId3LyricsText(text: String, encoding: Int): Boolean {

        val t = stripBomOnly(text)

        if (t.isBlank() || t.contains('\uFFFD')) return false

        if (encoding in 1..3) return true

        if (looksLikeMojibake(t)) return false

        return isRenderable(t)

    }



    private fun pickBestText(bytes: ByteArray, charsets: List<Charset>): String {

        if (bytes.isEmpty()) return ""

        val slice = stripByteOrderMark(bytes)

        if (slice.isEmpty()) return ""



        if (slice.size >= 2 && slice[0] == 0xFF.toByte() && slice[1] == 0xFE.toByte()) {

            return bestOrEmpty(stripBomOnly(String(slice, StandardCharsets.UTF_16LE)), slice)

        }

        if (slice.size >= 2 && slice[0] == 0xFE.toByte() && slice[1] == 0xFF.toByte()) {

            return bestOrEmpty(stripBomOnly(String(slice, utf16Be)), slice)

        }



        var bestText = ""

        var bestScore = Int.MIN_VALUE

        for (charset in charsets) {

            val decoded = runCatching { String(slice, charset) }.getOrNull() ?: continue

            if (decoded.contains('\uFFFD')) continue

            val cleaned = stripBomOnly(decoded)

            if (cleaned.isBlank() || looksLikeMojibake(cleaned)) continue

            val score = scoreDecoded(cleaned, slice, charset)

            if (score > bestScore) {

                bestScore = score

                bestText = cleaned

            }

        }

        return bestText

    }



    private fun bestOrEmpty(text: String, raw: ByteArray): String {

        val cleaned = stripBomOnly(text)

        return if (cleaned.isNotBlank() && !looksLikeMojibake(cleaned)) cleaned else ""

    }



    private fun charsetsForBytes(bytes: ByteArray): List<Charset> {

        val slice = stripByteOrderMark(bytes)

        val hasHigh = slice.any { (it.toInt() and 0xFF) >= 0x80 }

        val utf8Valid = isValidUtf8(slice)

        return when {

            utf8Valid -> listOf(

                StandardCharsets.UTF_8,

                gb18030,

                gbk,

                StandardCharsets.UTF_16LE,

                utf16Be,

                StandardCharsets.ISO_8859_1,

            )

            hasHigh -> listOf(

                gb18030,

                gbk,

                StandardCharsets.UTF_8,

                StandardCharsets.UTF_16LE,

                utf16Be,

                StandardCharsets.ISO_8859_1,

            )

            else -> listOf(

                StandardCharsets.UTF_8,

                gb18030,

                gbk,

                StandardCharsets.ISO_8859_1,

            )

        }

    }



    fun isRenderable(text: String): Boolean {

        val t = stripBomOnly(text)

        if (t.length < 2 || t.contains('\uFFFD') || looksLikeMojibake(t)) return false

        var meaningful = 0

        for (c in t) {

            when {

                c == '\uFEFF' -> return false

                c.isLetterOrDigit() -> meaningful++

                c.code in 0x3040..0x9FFF || c.code in 0xAC00..0xD7AF -> meaningful += 2

                c.isWhitespace() -> {}

                c in "[]:.,，。！？、…—-（）()'" -> meaningful++

                c.code < 0x20 -> return false

            }

        }

        return meaningful >= 2

    }



    fun looksLikeMojibake(text: String): Boolean {

        val t = stripBomOnly(text)

        if (t.length < 4) return false

        if (t.contains('%') && t.any { it.code in 0x4E00..0x9FFF }) return true

        if (hanLatinHan.containsMatchIn(t)) return true



        var cjk = 0

        var latinLower = 0

        var latin1Supplement = 0

        var digit = 0

        for (c in t) {

            when {

                c.code in 0x4E00..0x9FFF -> cjk++

                c in 'a'..'z' -> latinLower++

                c.isDigit() -> digit++

                c.code in 0x00A0..0x00FF && c !in "…—" -> latin1Supplement++

            }

        }

        if (cjk >= 4 && latin1Supplement >= 2) return true

        if (cjk >= 6 && latinLower >= 3 && !lrcTimestamp.containsMatchIn(t)) return true

        if (cjk >= 4 && latinLower >= 2 && digit >= 1 && latin1Supplement >= 1) return true

        return false

    }



    private fun isValidUtf8(bytes: ByteArray): Boolean {

        var i = 0

        while (i < bytes.size) {

            val b = bytes[i].toInt() and 0xFF

            when {

                b <= 0x7F -> i++

                b in 0xC2..0xDF -> {

                    if (i + 1 >= bytes.size || !isUtf8Continuation(bytes[i + 1])) return false

                    i += 2

                }

                b == 0xE0 -> {

                    if (i + 2 >= bytes.size) return false

                    val b1 = bytes[i + 1].toInt() and 0xFF

                    if (b1 !in 0xA0..0xBF || !isUtf8Continuation(bytes[i + 2])) return false

                    i += 3

                }

                b in 0xE1..0xEC || b == 0xEE || b in 0xF0..0xF3 -> {

                    if (i + 2 >= bytes.size ||

                        !isUtf8Continuation(bytes[i + 1]) ||

                        !isUtf8Continuation(bytes[i + 2])

                    ) {

                        return false

                    }

                    i += 3

                }

                b == 0xED -> {

                    if (i + 2 >= bytes.size) return false

                    val b1 = bytes[i + 1].toInt() and 0xFF

                    if (b1 !in 0x80..0x9F || !isUtf8Continuation(bytes[i + 2])) return false

                    i += 3

                }

                b == 0xEF -> {

                    if (i + 2 >= bytes.size) return false

                    val b1 = bytes[i + 1].toInt() and 0xFF

                    if (b1 !in 0x80..0xBF || !isUtf8Continuation(bytes[i + 2])) return false

                    i += 3

                }

                b == 0xF4 -> {

                    if (i + 2 >= bytes.size) return false

                    val b1 = bytes[i + 1].toInt() and 0xFF

                    if (b1 !in 0x80..0x8F || !isUtf8Continuation(bytes[i + 2])) return false

                    i += 3

                }

                else -> return false

            }

        }

        return true

    }



    private fun isUtf8Continuation(b: Byte): Boolean = (b.toInt() and 0xC0) == 0x80



    private fun stripByteOrderMark(bytes: ByteArray): ByteArray {

        var start = 0

        if (bytes.size >= 3 &&

            bytes[0] == 0xEF.toByte() &&

            bytes[1] == 0xBB.toByte() &&

            bytes[2] == 0xBF.toByte()

        ) {

            start = 3

        }

        return if (start > 0) bytes.copyOfRange(start, bytes.size) else bytes

    }



    private fun scoreDecoded(text: String, raw: ByteArray, charset: Charset): Int {

        if (text.contains('\uFFFD') || looksLikeMojibake(text)) return -1000

        var score = 0

        var cjk = 0

        for (c in text) {

            when {

                c.isLetterOrDigit() -> score += 2

                c.code in 0x4E00..0x9FFF -> {

                    score += 4

                    cjk++

                }

                c.code in 0xAC00..0xD7AF -> {

                    score += 4

                    cjk++

                }

                c.isWhitespace() -> score += 1

                c in "[]:.;：，。！？、…—-（）()" -> score += 2

                else -> score -= 2

            }

        }

        val name = charset.name()

        val hasHigh = raw.any { (it.toInt() and 0xFF) >= 0x80 }

        val utf8Valid = isValidUtf8(raw)

        when {

            charset == StandardCharsets.UTF_8 && utf8Valid -> score += 80

            name.contains("GB", ignoreCase = true) && hasHigh && !utf8Valid -> score += 70

            charset == StandardCharsets.ISO_8859_1 && hasHigh -> score -= 60

            charset == StandardCharsets.UTF_8 && hasHigh && !utf8Valid -> score -= 40

        }

        if (cjk >= 4 && lrcTimestamp.containsMatchIn(text)) score += 30

        return score

    }

}


