package com.apcida.smishingdetector.backend.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Reserved for an approved production enrollment flow. Separate from the synthetic pilot token. */
interface ProductionCredentialSource {
    fun get(): String?
    fun clear()
    fun quarantine()
}

class ProductionCredentialStore(
    context: Context,
    private val preferenceName: String = "production_installation_credential",
    private val alias: String = "apcida_production_installation_v1",
    private val consentPreferenceName: String = "ai_protection_consent"
) : ProductionCredentialSource {
    private val appContext = context.applicationContext
    private val preferences = appContext
        .getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun hasStoredCredential(): Boolean =
        preferences.contains("data") || preferences.contains("iv")

    fun provision(token: String) {
        check(!contextRevocationPending()) { "Resolve pending remote deletion before re-enrollment" }
        require(Regex("[A-Za-z0-9_-]{43}").matches(token)) { "Invalid installation credential" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.US_ASCII))
        val encode = { bytes: ByteArray -> Base64.encodeToString(bytes, Base64.NO_WRAP) }
        check(preferences.edit().putString("iv", encode(cipher.iv))
            .putString("data", encode(encrypted)).remove("inference_quarantined").commit()) { "Could not store installation credential" }
    }

    override fun get(): String? =
        if (contextRevocationPending() || preferences.getBoolean("inference_quarantined", false)) null
        else getForRevocation()

    /** Retained encrypted only to authenticate the deletion-only request after local revocation. */
    fun getForRevocation(): String? {
        val iv = preferences.getString("iv", null) ?: return null
        val data = preferences.getString("data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.US_ASCII)
                .takeIf { Regex("[A-Za-z0-9_-]{43}").matches(it) }
        } catch (_: Exception) {
            clear()
            null
        }
    }

    private fun contextRevocationPending(): Boolean =
        appContext.getSharedPreferences(consentPreferenceName, Context.MODE_PRIVATE)
            .getBoolean("remote_deletion_pending", false)

    override fun quarantine() {
        check(preferences.edit().putBoolean("inference_quarantined", true).commit())
    }

    override fun clear() { preferences.edit().clear().commit() }
}
