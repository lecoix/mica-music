package com.mica.music.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogSessionRotationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun preservesPreviousProcessLogAndStartsAnEmptyCurrentLog() {
        val diagnosticsDir = temporaryFolder.newFolder("diagnostics")
        diagnosticsDir.resolve("current-session.log").writeText("lock-event\n")

        DiagnosticLog.rotateSessionLogs(diagnosticsDir)

        assertEquals("lock-event\n", diagnosticsDir.resolve("previous-session.log").readText())
        assertEquals("", diagnosticsDir.resolve("current-session.log").readText())
    }

    @Test
    fun emptyCurrentLogDoesNotOverwriteUsefulPreviousProcessLog() {
        val diagnosticsDir = temporaryFolder.newFolder("diagnostics")
        diagnosticsDir.resolve("current-session.log").writeText("")
        diagnosticsDir.resolve("previous-session.log").writeText("older-lock-event\n")

        DiagnosticLog.rotateSessionLogs(diagnosticsDir)

        assertEquals(
            "older-lock-event\n",
            diagnosticsDir.resolve("previous-session.log").readText(),
        )
    }
}
