package com.apcida.smishingdetector.backend.consent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated preference file; no production endpoint, credential or SMS is accessed. */
@RunWith(AndroidJUnit4::class)
class ProductionConsentRevocationInstrumentedTest {
    @Test fun localOptOutAndDeletionMarkerPersistAcrossStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "production_revocation_test_" + UUID.randomUUID()
        try {
            val first = AiProtectionConsentStore(context, preferenceName,
                hasProductionCredential = { true }, enqueueDeletion = {})
            first.grant(1000)
            assertTrue(first.snapshot().enabled)
            first.revoke()
            val reopened = AiProtectionConsentStore(context, preferenceName,
                hasProductionCredential = { false }, enqueueDeletion = {})
            assertFalse(reopened.snapshot().enabled)
            assertTrue(reopened.remoteDeletionPending())
            assertEquals(AiProtectionConsentStore.RemoteDeletionStatus.PENDING,
                reopened.remoteDeletionStatus())
            reopened.grant(2000)
            assertTrue(reopened.remoteDeletionPending())
            reopened.markRemoteDeletionConfirmed()
            assertFalse(reopened.remoteDeletionPending())
            assertEquals(AiProtectionConsentStore.RemoteDeletionStatus.CONFIRMED,
                reopened.remoteDeletionStatus())
        } finally {
            context.getSharedPreferences(preferenceName, 0).edit().clear().commit()
        }
    }
}
