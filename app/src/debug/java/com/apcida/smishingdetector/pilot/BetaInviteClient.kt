package com.apcida.smishingdetector.pilot

import com.apcida.smishingdetector.backend.network.CloudflareAccessCredential
import com.apcida.smishingdetector.backend.network.ProductionEndpointPolicy
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Debug-only invite redemption. The invitation itself is a short-lived one-use secret. */
internal object BetaInviteClient {
    data class Bundle(val installationToken: String, val clientId: String,
                      val clientSecret: String)

    fun redeem(host: String, code: String): Bundle {
        val origin = "https://$host"
        require(ProductionEndpointPolicy.isApprovedHttpsOrigin(origin, host) &&
            !host.endsWith(".trycloudflare.com", ignoreCase = true)) { "Approved host unavailable" }
        require(Regex("[A-Za-z0-9_-]{43}").matches(code)) { "Invalid invite format" }
        val connection = (URL("$origin/api/v1/enrollment").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 20000
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        try {
            connection.outputStream.use { stream ->
                stream.write(JSONObject().put("inviteCode", code).toString().toByteArray(Charsets.UTF_8))
            }
            if (connection.responseCode != 201) throw IllegalStateException("Invite rejected or setup unavailable")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val token = json.getString("installationToken")
            require(Regex("[A-Za-z0-9_-]{43}").matches(token)) { "Invalid server response" }
            val credential = CloudflareAccessCredential(json.getString("clientId"),
                json.getString("clientSecret"))
            return Bundle(token, credential.clientId, credential.clientSecret)
        } finally {
            connection.disconnect()
        }
    }
}
