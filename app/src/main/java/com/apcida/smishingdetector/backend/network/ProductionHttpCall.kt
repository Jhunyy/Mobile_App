package com.apcida.smishingdetector.backend.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancel the socket when its WorkManager/coroutine request is canceled. */
internal suspend fun <T> OkHttpClient.executeCancellable(
    request: Request, map: (Response) -> T
): T = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    try {
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    val value = response.use(map)
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    } catch (error: Exception) {
        if (continuation.isActive) continuation.resumeWithException(error)
    }
}
