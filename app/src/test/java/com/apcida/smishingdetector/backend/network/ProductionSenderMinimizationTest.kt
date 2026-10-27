package com.apcida.smishingdetector.backend.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductionSenderMinimizationTest {
    @Test fun numericPhoneNumberCanBeReplacedWithoutExposingDigits() {
        assertEquals("PHONE_NUMBER", ProductionSenderMinimization.candidate("+63 917 123 4567"))
        assertEquals("PHONE_NUMBER", ProductionSenderMinimization.candidate("(02) 8123-4567"))
    }

    @Test fun shortCodesAndBrandClaimsRemainAvailableForFutureReview() {
        assertEquals("12345", ProductionSenderMinimization.candidate("12345"))
        assertEquals("BDO", ProductionSenderMinimization.candidate(" BDO "))
        assertEquals("Unknown", ProductionSenderMinimization.candidate("Unknown"))
    }
}
