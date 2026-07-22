package com.mica.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueReorderDragSessionTest {

    @Test
    fun `forward projection maps visual indices without a copied list`() {
        val projection = QueueMoveProjection(fromIndex = 2, toIndex = 5)

        assertEquals(listOf(0, 1, 3, 4, 5, 2, 6), (0..6).map(projection::sourceIndexAt))
    }

    @Test
    fun `backward projection maps visual indices without a copied list`() {
        val projection = QueueMoveProjection(fromIndex = 5, toIndex = 2)

        assertEquals(listOf(0, 1, 5, 2, 3, 4, 6), (0..6).map(projection::sourceIndexAt))
    }

    @Test
    fun `projection remains constant size for a ten-thousand item queue`() {
        val projection = QueueMoveProjection(fromIndex = 9_999, toIndex = 0)

        assertEquals(9_999, projection.sourceIndexAt(0))
        assertEquals(0, projection.sourceIndexAt(1))
        assertEquals(9_998, projection.sourceIndexAt(9_999))
    }

    @Test
    fun `many preview moves collapse to one original-to-final commit`() {
        val session = QueueReorderDragSession()

        session.recordPreviewMove(fromIndex = 100, toIndex = 101)
        session.recordPreviewMove(fromIndex = 101, toIndex = 150)
        session.recordPreviewMove(fromIndex = 150, toIndex = 130)

        assertEquals(QueueReorderCommit(fromIndex = 100, toIndex = 130), session.finish())
        assertNull(session.finish())
    }

    @Test
    fun `drag returning to original position produces no service commit`() {
        val session = QueueReorderDragSession()

        session.recordPreviewMove(fromIndex = 25, toIndex = 30)
        session.recordPreviewMove(fromIndex = 30, toIndex = 25)

        assertNull(session.finish())
    }
}
