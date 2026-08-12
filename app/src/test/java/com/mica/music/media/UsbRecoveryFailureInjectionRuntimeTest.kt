package com.mica.music.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbRecoveryFailureInjectionRuntimeTest {

    @After
    fun clearRuntime() {
        UsbRecoveryFailureInjectionRuntime.clear()
    }

    @Test
    fun consumesExactlyArmedFailureBudget() {
        val generation = UsbRecoveryFailureInjectionRuntime.arm(3)

        assertEquals(
            UsbRecoveryInjectedFailure(generation, attempt = 1, remainingFailures = 2),
            UsbRecoveryFailureInjectionRuntime.consume(),
        )
        assertEquals(
            UsbRecoveryInjectedFailure(generation, attempt = 2, remainingFailures = 1),
            UsbRecoveryFailureInjectionRuntime.consume(),
        )
        assertEquals(
            UsbRecoveryInjectedFailure(generation, attempt = 3, remainingFailures = 0),
            UsbRecoveryFailureInjectionRuntime.consume(),
        )
        assertNull(UsbRecoveryFailureInjectionRuntime.consume())
    }

    @Test
    fun replacementPlanInvalidatesOldBudget() {
        val oldGeneration = UsbRecoveryFailureInjectionRuntime.arm(3)
        assertEquals(oldGeneration, UsbRecoveryFailureInjectionRuntime.consume()?.generation)

        val replacementGeneration = UsbRecoveryFailureInjectionRuntime.arm(1)

        assertTrue(replacementGeneration > oldGeneration)
        assertEquals(
            UsbRecoveryInjectedFailure(replacementGeneration, attempt = 1, remainingFailures = 0),
            UsbRecoveryFailureInjectionRuntime.consume(),
        )
        assertNull(UsbRecoveryFailureInjectionRuntime.consume())
    }
}
