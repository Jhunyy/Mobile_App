package com.apcida.smishingdetector.backend.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactRiskAdjustmentTest {
    @Test
    fun savedContactGetsAtMostFivePointDiscountWithinSameRiskBand() {
        for ((original, adjusted) in listOf(
            0f to 0f, 25f to 20f, 30f to 30f, 34f to 30f,
            59f to 54f, 60f to 60f, 64f to 60f, 100f to 95f
        )) {
            assertEquals(adjusted, ContactRiskAdjustment.apply(original, true), 0f)
        }
    }

    @Test
    fun unmatchedNumberKeepsOriginalScore() {
        assertEquals(35f, ContactRiskAdjustment.apply(35f, false), 0f)
    }
}
