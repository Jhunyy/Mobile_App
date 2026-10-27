package com.apcida.smishingdetector.backend.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PilotCredentialStoreInstrumentedTest {
    @Test fun credentialPersistsAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = PilotCredentialStore(context)
        store.clear()
        val token = "b".repeat(43)
        store.provision(token)
        assertEquals(token, PilotCredentialStore(context).get())
        store.clear()
        assertNull(PilotCredentialStore(context).get())
    }
}
