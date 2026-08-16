package com.mica.music.media

import org.junit.Assert.assertEquals
import org.junit.Test

class Media3PlaylistIndexSemanticsTest {
    @Test
    fun removeCurrentFirstKeepsSuccessorAtIndexZero() {
        // [A,B,C] current A index0 -> remove(0) => [B,C] current B index0
        assertEquals(0, Media3PlaylistIndexSemantics.currentIndexAfterRemove(3, 0, 0, 1))
    }

    @Test
    fun removeCurrentMiddleKeepsSuccessorAtSameIndex() {
        // [A,B,C] current B index1 -> remove(1) => [A,C] current C index1
        assertEquals(1, Media3PlaylistIndexSemantics.currentIndexAfterRemove(3, 1, 1, 2))
    }

    @Test
    fun removeCurrentLastFallsBackToFirstWindow() {
        // [C,D,X] current X index2 -> remove(2) => [C,D] current C index0
        assertEquals(0, Media3PlaylistIndexSemantics.currentIndexAfterRemove(3, 2, 2, 3))
    }

    @Test
    fun removeCurrentRangeWithSuccessorAfterRangeSelectsFromIndex() {
        // [A,B,C,D] current B index1 -> remove B,C => [A,D] current D index1
        assertEquals(1, Media3PlaylistIndexSemantics.currentIndexAfterRemove(4, 1, 1, 3))
    }

    @Test
    fun removeCurrentRangeWithoutSuccessorFallsBackToFirstWindow() {
        // [A,B,C] current B index1 -> remove B,C => [A] current A index0
        assertEquals(0, Media3PlaylistIndexSemantics.currentIndexAfterRemove(3, 1, 1, 3))
    }

    @Test
    fun removeNonCurrentBeforeCurrentShiftsIndex() {
        // [A,B,C] current C index2 -> remove A => [B,C] current C index1
        assertEquals(1, Media3PlaylistIndexSemantics.currentIndexAfterRemove(3, 2, 0, 1))
    }

    @Test
    fun removeNonCurrentAfterCurrentKeepsIndex() {
        // [A,B,C] current A index0 -> remove C => [A,B] current A index0
        assertEquals(0, Media3PlaylistIndexSemantics.currentIndexAfterRemove(3, 0, 2, 3))
    }

    @Test
    fun removeAllRemainingSelectsZero() {
        assertEquals(0, Media3PlaylistIndexSemantics.currentIndexAfterRemove(2, 0, 0, 2))
    }
}
