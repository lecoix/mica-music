package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HiResBadgeStyleTest {
    @Test
    fun fromStorage_fallsBackToDefault() {
        assertEquals(HiResBadgeStyle.DEFAULT, HiResBadgeStyle.fromStorage(null))
        assertEquals(HiResBadgeStyle.DEFAULT, HiResBadgeStyle.fromStorage("unknown"))
    }

    @Test
    fun fromStorage_restoresKnownValues() {
        assertEquals(
            HiResBadgeStyle.GOLD_LABEL,
            HiResBadgeStyle.fromStorage(HiResBadgeStyle.GOLD_LABEL.storageValue),
        )
        assertEquals(
            HiResBadgeStyle.CUSTOM_IMAGE,
            HiResBadgeStyle.fromStorage(HiResBadgeStyle.CUSTOM_IMAGE.storageValue),
        )
    }
}
