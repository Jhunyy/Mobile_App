package com.apcida.smishingdetector.backend.network

/** Staging budget: server 90 s, client 115 s, proxy default 125 s, orphan recovery 150 s. */
object ProductionTiming {
    const val CONNECT_SECONDS = 10L
    const val READ_SECONDS = 110L
    const val CALL_SECONDS = 115L
    const val ORPHAN_STALE_MS = 150_000L
}
