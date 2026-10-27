package com.apcida.smishingdetector.evaluation

import com.apcida.smishingdetector.model.data.RiskLevel
import java.time.Instant

data class EvaluationInput(
    val messageId: String,
    val messageText: String,
    val verifiedLabel: RiskLevel?,
    val senderAddress: String? = null,
    val receivedAt: Long? = null,
    val isSavedContact: Boolean = false,
    val inputError: String? = null
)

/** Small RFC 4180-style codec; quoted commas, quotes, and line breaks are supported. */
object EvaluationCsv {
    fun parse(text: String): List<EvaluationInput> {
        val rows = decode(text.removePrefix("\uFEFF"))
        require(rows.isNotEmpty()) { "CSV is empty." }
        val header = rows.first().map { it.trim().lowercase() }
        require(header.size == header.toSet().size) { "CSV has duplicate column names." }
        listOf("message_id", "message_text", "verified_label").forEach {
            require(it in header) { "Missing required column: $it" }
        }
        val seenIds = mutableSetOf<String>()
        return rows.drop(1).mapIndexed { index, cells ->
            fun field(name: String): String = header.indexOf(name).takeIf { it >= 0 }
                ?.let { cells.getOrNull(it) }.orEmpty()
            val id = field("message_id").trim()
            val body = field("message_text")
            val labelText = field("verified_label").trim().uppercase()
            val label = when (labelText) {
                "SAFE" -> RiskLevel.SAFE
                "SCAM" -> RiskLevel.SCAM
                else -> null
            }
            val sender = field("sender_address").trim().takeIf { it.isNotEmpty() }
            val timeText = field("received_at").trim()
            val time = if (timeText.isEmpty()) null else runCatching {
                Instant.parse(timeText).toEpochMilli()
            }.getOrNull()
            val savedText = field("is_saved_contact").trim().lowercase()
            val error = when {
                cells.size != header.size -> "Column count differs from header on row ${index + 2}."
                id.isEmpty() -> "Missing message_id."
                !seenIds.add(id) -> "Duplicate message_id."
                body.isBlank() -> "Missing message_text."
                body.length > 4000 -> "message_text exceeds 4000 characters."
                label == null -> "Invalid verified_label; use SAFE or SCAM."
                timeText.isNotEmpty() && time == null -> "Invalid received_at; use ISO 8601 UTC."
                savedText !in setOf("", "true", "false") -> "Invalid is_saved_contact; use true or false."
                else -> null
            }
            EvaluationInput(id, body, label, sender, time, savedText == "true", error)
        }
    }

    fun encode(rows: List<List<Any?>>): String = rows.joinToString("\r\n", postfix = "\r\n") {
        row -> row.joinToString(",") { value ->
            val raw = value?.toString().orEmpty()
            // Spreadsheet programs may execute formulas even in quoted CSV fields.
            val safe = if (raw.firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) "'$raw" else raw
            "\"${safe.replace("\"", "\"\"")}\""
        }
    }

    private fun decode(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var index = 0
        fun endField() { row.add(field.toString()); field.setLength(0); closedQuote = false }
        fun endRow() { endField(); rows.add(row.toList()); row.clear() }
        while (index < text.length) {
            val char = text[index]
            if (quoted) {
                if (char == '"' && index + 1 < text.length && text[index + 1] == '"') {
                    field.append('"'); index++
                } else if (char == '"') {
                    quoted = false; closedQuote = true
                } else field.append(char)
            } else when (char) {
                '"' -> {
                    require(field.isEmpty() && !closedQuote) { "Unexpected quote in CSV." }
                    quoted = true
                }
                ',' -> endField()
                '\n' -> endRow()
                '\r' -> {
                    if (index + 1 < text.length && text[index + 1] == '\n') index++
                    endRow()
                }
                else -> {
                    require(!closedQuote) { "Unexpected text after quoted CSV field." }
                    field.append(char)
                }
            }
            index++
        }
        require(!quoted) { "CSV has an unterminated quoted field." }
        if (field.isNotEmpty() || row.isNotEmpty() || closedQuote) endRow()
        return rows
    }
}
