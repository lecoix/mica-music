package com.mica.music.media.dsd

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Test

class Sk02RawDataEvidenceTest {

    @Test
    fun alt4FixtureRecordsRawDataWithoutInventingNativeFraming() {
        val fixture = listOf(
            File("src/test/resources/usb/p5/sk02-alt4-raw-data.properties"),
            File("app/src/test/resources/usb/p5/sk02-alt4-raw-data.properties"),
        ).firstOrNull(File::isFile) ?: error("SK02 P5 RAW_DATA fixture is missing")
        val properties = Properties().apply {
            fixture.inputStream().use(::load)
        }

        assertEquals("0x262a", properties.getProperty("vid"))
        assertEquals("0x0001", properties.getProperty("pid"))
        assertEquals("4", properties.getProperty("alternateSetting"))
        assertEquals("0x80000000", properties.getProperty("bmFormats"))
        assertEquals("RAW_DATA", properties.getProperty("formatMeaning"))
        assertEquals("4", properties.getProperty("subslotBytes"))
        assertEquals("32", properties.getProperty("bitResolution"))
        assertEquals("0x03", properties.getProperty("outEndpoint"))
        assertEquals("0x84", properties.getProperty("feedbackEndpoint"))
        assertEquals("400", properties.getProperty("maxPacketBytes"))
        assertEquals("false", properties.getProperty("nativeFramingProven"))
    }
}
