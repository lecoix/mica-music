package com.mica.music.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistNamesTest {

    @After
    fun resetConfig() {
        ArtistNames.configure(ArtistSplitConfig())
    }

    @Test
    fun eachEnabledSeparatorCanSplitIndependently() {
        val cases = mapOf(
            ArtistSeparator.COMMA to "A,B",
            ArtistSeparator.FULL_WIDTH_COMMA to "A，B",
            ArtistSeparator.SEMICOLON to "A;B",
            ArtistSeparator.FULL_WIDTH_SEMICOLON to "A；B",
            ArtistSeparator.AMPERSAND to "A&B",
            ArtistSeparator.MULTIPLICATION_SIGN to "A×B",
            ArtistSeparator.SLASH to "A/B",
            ArtistSeparator.FULL_WIDTH_SLASH to "A／B",
            ArtistSeparator.IDEOGRAPHIC_COMMA to "A、B",
            ArtistSeparator.PIPE to "A|B",
            ArtistSeparator.FEAT to "A feat. B",
            ArtistSeparator.FT to "A FT. B",
        )

        cases.forEach { (separator, raw) ->
            ArtistNames.configure(ArtistSplitConfig(enabledSeparators = setOf(separator)))
            assertEquals("separator=$separator", listOf("A", "B"), ArtistNames.split(raw))
        }
    }

    @Test
    fun disabledSeparatorsRemainUntouched() {
        ArtistNames.configure(ArtistSplitConfig(enabledSeparators = emptySet()))

        assertEquals(listOf("A / B feat. C"), ArtistNames.split("A / B feat. C"))
    }

    @Test
    fun exactWhitelistEntryProtectsWholeFieldIgnoringCase() {
        ArtistNames.configure(
            ArtistSplitConfig(
                enabledSeparators = setOf(ArtistSeparator.SLASH, ArtistSeparator.AMPERSAND),
                whitelist = listOf("AC/DC"),
            ),
        )

        assertEquals(listOf("ac/dc"), ArtistNames.split("ac/dc"))
        assertEquals(listOf("AC / DC"), ArtistNames.split("AC / DC"))
        assertEquals(listOf("AC", "DC", "Guest"), ArtistNames.split("AC/DC & Guest"))
    }

    @Test
    fun pathologicalTagHasBoundedArtistExpansionWithoutDroppingRemainder() {
        ArtistNames.configure(ArtistSplitConfig(enabledSeparators = setOf(ArtistSeparator.COMMA)))
        val raw = (1..100).joinToString(",") { "Artist $it" }

        val artists = ArtistNames.split(raw)

        assertEquals(ArtistNames.MAX_ARTISTS_PER_TAG, artists.size)
        assertEquals(true, artists.last().endsWith("Artist 100"))
    }
}
