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

interface PilotCredentialSource {
    fun get(): String?
    fun clear()
}

/** Per-install secret, encrypted with a non-exportable Android Keystore key. Backups are disabled. */
class PilotCredentialStore(context: Context) : PilotCredentialSource {
    private val preferences = context.applicationContext.getSharedPreferences("pilot_credential", Context.MODE_PRIVATE)
    private val alias = "apcida_pilot_credential_v1"

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

    fun provision(token: String) {
        require(Regex("[A-Za-z0-9_-]{43}").matches(token)) { "Invalid pilot credential" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.US_ASCII))
        val encode = { bytes: ByteArray -> Base64.encodeToString(bytes, Base64.NO_WRAP) }
        check(preferences.edit().putString("iv", encode(cipher.iv))
            .putString("data", encode(encrypted)).commit()) { "Could not store pilot credential" }
    }

    override fun get(): String? {
        val iv = preferences.getString("iv", null) ?: return null
        val data = preferences.getString("data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val value = String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.US_ASCII)
            value.takeIf { Regex("[A-Za-z0-9_-]{43}").matches(it) }
        } catch (_: Exception) {
            clear()
            null
        }
    }

    override fun clear() { preferences.edit().clear().commit() }
}
