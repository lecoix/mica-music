package com.mica.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueReorderDragSessionTest {

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
