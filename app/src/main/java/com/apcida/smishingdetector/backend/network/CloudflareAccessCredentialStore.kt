package com.apcida.smishingdetector.backend.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One installation's Cloudflare Access credential. Never put these values in BuildConfig or an APK. */
class CloudflareAccessCredential(val clientId: String, val clientSecret: String) {
    init {
        require(Regex("[A-Za-z0-9_-]{1,120}\\.access").matches(clientId))
        require(Regex("[A-Za-z0-9_-]{32,256}").matches(clientSecret))
    }

    override fun toString(): String = "CloudflareAccessCredential(redacted)"
}

fun interface CloudflareAccessCredentialSource {
    fun get(): CloudflareAccessCredential?
}

/** Encrypted at rest with an Android Keystore key; awaiting a reviewed per-device enrollment flow. */
class CloudflareAccessCredentialStore(context: Context) : CloudflareAccessCredentialSource {
    private val preferences = context.applicationContext
        .getSharedPreferences("cloudflare_access_credential", Context.MODE_PRIVATE)
    private val alias = "apcida_cloudflare_access_v1"

    fun hasStoredCredential(): Boolean = preferences.contains("iv") || preferences.contains("data")

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

    fun provision(clientId: String, clientSecret: String) {
        check(!hasStoredCredential()) { "Resolve existing Access credential before enrollment" }
        val credential = CloudflareAccessCredential(clientId, clientSecret)
        val payload = JsonObject().apply {
            addProperty("clientId", credential.clientId)
            addProperty("clientSecret", credential.clientSecret)
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(payload)
        val encode = { bytes: ByteArray -> Base64.encodeToString(bytes, Base64.NO_WRAP) }
        check(preferences.edit().putString("iv", encode(cipher.iv))
            .putString("data", encode(encrypted)).commit()) { "Could not store Access credential" }
    }

    override fun get(): CloudflareAccessCredential? {
        val iv = preferences.getString("iv", null) ?: return null
        val data = preferences.getString("data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val json = JsonParser().parse(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)),
                Charsets.UTF_8)).asJsonObject
            CloudflareAccessCredential(json.get("clientId").asString, json.get("clientSecret").asString)
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Could not clear Access credential" }
    }
}
