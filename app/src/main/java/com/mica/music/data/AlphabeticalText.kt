package com.mica.music.data

import java.text.Collator
import java.text.Normalizer
import java.nio.charset.Charset
import java.util.IdentityHashMap
import java.util.Locale

object AlphabeticalText {
    private val markRegex = "\\p{Mn}+".toRegex()
    private val gbkCharset = Charset.forName("GBK")
    private val gbkPinyinValues = intArrayOf(
        -20319, -20283, -19775, -19218, -18710, -18526, -18239, -17922, -17417,
        -16474, -16212, -15640, -15165, -14922, -14914, -14630, -14149, -14090,
        -13318, -12838, -12556, -11847, -11055,
    )
    private val gbkPinyinInitials = charArrayOf(
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M', 'N',
        'O', 'P', 'Q', 'R', 'S', 'T', 'W', 'X', 'Y', 'Z',
    )

    fun sectionFor(value: String): String {
        val key = normalizedText(value)
        val initial = key.firstOrNull() ?: return "#"
        return if (initial in 'A'..'Z') initial.toString() else "#"
    }

    fun sortKey(value: String): String {
        val text = normalizedText(value)
        val section = sectionForNormalized(text)
        val sectionRank = if (section == "#") 1 else 0
        return "$sectionRank|$text"
    }

    fun <T : Any> comparator(
        selector: (T) -> String,
        collator: Collator,
    ): Comparator<T> {
        val keys = IdentityHashMap<T, String>()
        return Comparator { a, b ->
            val aText = selector(a)
            val bText = selector(b)
            val keyCompare = keys.getOrPut(a) { sortKey(aText) }
                .compareTo(keys.getOrPut(b) { sortKey(bText) })
            if (keyCompare != 0) keyCompare else collator.compare(aText, bText)
        }
    }

    private fun normalizeSortKey(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(markRegex, "")
            .uppercase(Locale.ROOT)
            .trim()

    private fun normalizedText(value: String): String =
        normalizeSortKey(AndroidIcu.transliterate(value) ?: fallbackSortKey(value))

    private fun sectionForNormalized(value: String): String {
        val initial = value.firstOrNull() ?: return "#"
        return if (initial in 'A'..'Z') initial.toString() else "#"
    }

    private fun fallbackSortKey(value: String): String = buildString {
        value.forEach { char ->
            append(cjkInitial(char) ?: char)
        }
    }

    private fun cjkInitial(char: Char): Char? {
        if (Character.UnicodeBlock.of(char) != Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
            return null
        }
        val bytes = char.toString().toByteArray(gbkCharset)
        if (bytes.size < 2) return null
        val code = bytes[0].toInt() * 256 + bytes[1].toInt()
        if (code < gbkPinyinValues.first()) return 'A'
        var index = gbkPinyinValues.lastIndex
        while (index >= 0 && code < gbkPinyinValues[index]) {
            index--
        }
        return if (index >= 0) gbkPinyinInitials[index] else '#'
    }
}

private object AndroidIcu {
    private val transliterator: Any? by lazy {
        runCatching {
            val clazz = Class.forName("android.icu.text.Transliterator")
            clazz.getMethod("getInstance", String::class.java)
                .invoke(null, "Han-Latin; Latin-ASCII")
        }.getOrNull()
    }

    fun transliterate(value: String): String? {
        val instance = transliterator ?: return null
        return runCatching {
            instance.javaClass.getMethod("transliterate", String::class.java)
                .invoke(instance, value) as? String
        }.getOrNull()
    }
}
