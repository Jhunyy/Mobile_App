package com.apcida.smishingdetector.backend.consent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a physical device after secure transport is provisioned. Does not send SMS. */
@RunWith(AndroidJUnit4::class)
class AiProtectionConsentStoreTest {
    @Test fun prelaunchConsentRequiresFreshDisclosure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("ai_protection_consent", 0)
        preferences.edit().clear().putBoolean("enabled", true)
            .putInt("disclosure_version", 1).putLong("granted_at", 1234)
            .putLong("generation", 1).commit()
        val store = AiProtectionConsentStore(context)
        assertFalse(store.snapshot().enabled)
        assertTrue(store.grant(2345).enabled)
        assertEquals(AiProtectionConsentStore.DISCLOSURE_VERSION,
            preferences.getInt("disclosure_version", 0))
        store.revoke()
    }

    @Test fun defaultOffPersistsAndRevocationChangesGeneration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("ai_protection_consent", 0).edit().clear().commit()
        val store = AiProtectionConsentStore(context)
        assertFalse(store.snapshot().enabled)
        assertEquals(0, store.snapshot().grantedAt)
        assertFalse(store.hasSeenDisclosure())
        store.markDisclosureSeen()
        assertFalse(AiProtectionConsentStore(context).snapshot().enabled)
        val granted = store.grant(1234)
        assertTrue(AiProtectionConsentStore(context).snapshot().enabled)
        assertEquals(1234, granted.grantedAt)
        store.revoke()
        val revoked = AiProtectionConsentStore(context).snapshot()
        assertFalse(revoked.enabled)
        assertEquals(0, revoked.grantedAt)
        assertTrue(revoked.generation > granted.generation)
    }
}
