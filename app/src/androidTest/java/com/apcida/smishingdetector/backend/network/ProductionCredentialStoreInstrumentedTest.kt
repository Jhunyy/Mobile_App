package com.apcida.smishingdetector.backend.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

/** Uses isolated test preferences and Keystore alias; never touches a provisioned credential. */
@RunWith(AndroidJUnit4::class)
class ProductionCredentialStoreInstrumentedTest {
    @Test fun encryptedCredentialPersistsAcrossStoreRecreationAndClears() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val prefsName = "production_credential_test_$suffix"
        val alias = "apcida_production_credential_test_$suffix"
        val consentPrefsName = "production_consent_test_$suffix"
        val token = "a".repeat(43)
        try {
            val first = ProductionCredentialStore(context, prefsName, alias, consentPrefsName)
            assertNull(first.get())
            first.provision(token)
            val stored = context.getSharedPreferences(prefsName, 0).all.toString()
            assertFalse(stored.contains(token))
            assertEquals(token, ProductionCredentialStore(context, prefsName, alias, consentPrefsName).get())
            first.quarantine()
            assertNull(first.get())
            val reopened = ProductionCredentialStore(context, prefsName, alias, consentPrefsName)
            assertNull(reopened.get())
            assertEquals(token, reopened.getForRevocation())
            try { first.provision(token); fail("Quarantined token was accepted for inference") }
            catch (_: IllegalStateException) { }
            assertEquals(token, first.getForRevocation())
            context.getSharedPreferences(consentPrefsName, 0).edit()
                .putBoolean("remote_deletion_pending", true).commit()
            assertNull(first.get())
            assertEquals(token, first.getForRevocation())
            first.clear()
            assertNull(ProductionCredentialStore(context, prefsName, alias, consentPrefsName).get())
        } finally {
            context.getSharedPreferences(prefsName, 0).edit().clear().commit()
            context.getSharedPreferences(consentPrefsName, 0).edit().clear().commit()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
