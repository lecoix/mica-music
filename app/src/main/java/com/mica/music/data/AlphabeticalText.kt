package com.mica.music.data

import java.io.File
import java.text.Collator
import java.text.Normalizer
import java.nio.charset.Charset
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.Locale

object AlphabeticalText {
    // 10k catalog worst-case QA exercises distinct title + artist + album keys (~30k).
    // Keep that working set hot without making the process cache unbounded.
    private const val NORMALIZED_TEXT_CACHE_MAX_ENTRIES = 32_768
    private const val SORT_KEY_ALGORITHM_VERSION = 1
    private val persistentSortKeyCache = PersistentSortKeyCache(
        algorithmVersion = SORT_KEY_ALGORITHM_VERSION,
        maxEntries = NORMALIZED_TEXT_CACHE_MAX_ENTRIES,
    )
    private val markRegex = "\\p{Mn}+".toRegex()
    private val gbkCharset = Charset.forName("GBK")
    private val normalizedTextCache = object : LinkedHashMap<String, String>(
        NORMALIZED_TEXT_CACHE_MAX_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > NORMALIZED_TEXT_CACHE_MAX_ENTRIES
    }
    private val gbkPinyinValues = intArrayOf(
        -20319, -20283, -19775, -19218, -18710, -18526, -18239, -17922, -17417,
        -16474, -16212, -15640, -15165, -14922, -14914, -14630, -14149, -14090,
        -13318, -12838, -12556, -11847, -11055,
    )
    private val gbkPinyinInitials = charArrayOf(
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M', 'N',
        'O', 'P', 'Q', 'R', 'S', 'T', 'W', 'X', 'Y', 'Z',
    )

    internal fun configurePersistentCache(file: File) {
        persistentSortKeyCache.configure(file)
    }

    internal fun preloadPersistentCache() {
        persistentSortKeyCache.preload()
    }

    internal fun persistPersistentCache() {
        persistentSortKeyCache.persist()
    }

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

    private fun normalizedText(value: String): String {
        synchronized(normalizedTextCache) {
            normalizedTextCache[value]?.let { return it }
        }
        val ascii = value.isAscii()
        if (!ascii) {
            persistentSortKeyCache[value]?.let { persisted ->
                synchronized(normalizedTextCache) {
                    normalizedTextCache[value] = persisted
                }
                return persisted
            }
        }
        val transliterated = if (ascii) {
            value
        } else {
            AndroidIcu.transliterate(value) ?: fallbackSortKey(value)
        }
        val normalized = normalizeSortKey(transliterated)
        synchronized(normalizedTextCache) {
            normalizedTextCache[value] = normalized
        }
        if (!ascii) persistentSortKeyCache[value] = normalized
        return normalized
    }

    private fun String.isAscii(): Boolean = all { it.code < 0x80 }

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

    private val transliterateMethod by lazy {
        transliterator?.javaClass?.getMethod("transliterate", String::class.java)
    }

    fun transliterate(value: String): String? {
        val instance = transliterator ?: return null
        val method = transliterateMethod ?: return null
        return runCatching {
            method.invoke(instance, value) as? String
        }.getOrNull()
    }
}
