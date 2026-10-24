package com.apcida.smishingdetector.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "messages", indices = [
    Index(value = ["sender_hash", "received_at"]),
    Index(value = ["source_sms_id"], unique = true)
])
data class Message(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "message_id")
    val messageId: Long = 0,

    @ColumnInfo(name = "sender_hash")
    val senderHash: String? = null,

    @ColumnInfo(name = "sender_address")
    val senderAddress: String? = null,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "content_hash")
    val contentHash: String? = null,

    @ColumnInfo(name = "source_sms_id")
    val sourceSmsId: Long? = null,

    @ColumnInfo(name = "alerts_enabled", defaultValue = "1")
    val alertsEnabled: Boolean = true,

    @ColumnInfo(name = "received_at")
    val receivedAt: Long,

    @ColumnInfo(name = "risk_score")
    val riskScore: Float = 0f,

    @ColumnInfo(name = "combined_risk_score")
    val combinedRiskScore: Int? = null,

    @ColumnInfo(name = "rule_risk_points", defaultValue = "0")
    val ruleRiskPoints: Int = 0,

    @ColumnInfo(name = "ai_risk_points", defaultValue = "0")
    val aiRiskPoints: Int = 0,

    @ColumnInfo(name = "history_risk_points", defaultValue = "0")
    val historyRiskPoints: Int = 0,

    @ColumnInfo(name = "sender_risk_points", defaultValue = "0")
    val senderRiskPoints: Int = 0,

    @ColumnInfo(name = "risk_level")
    val riskLevel: String = "SAFE",

    @ColumnInfo(name = "deterministic_risk_level", defaultValue = "'SAFE'")
    val deterministicRiskLevel: String = "SAFE",

    @ColumnInfo(name = "matched_indicators", defaultValue = "''")
    val matchedIndicators: String = "",

    @ColumnInfo(name = "is_saved_contact", defaultValue = "0")
    val isSavedContact: Boolean = false,

    @ColumnInfo(name = "has_strong_rule_signal", defaultValue = "0")
    val hasStrongRuleSignal: Boolean = false,

    @ColumnInfo(name = "is_flagged")
    val isFlagged: Boolean = false,

    @ColumnInfo(name = "gemma_invoked")
    val gemmaInvoked: Boolean = false,

    @ColumnInfo(name = "gemma_classification")
    val gemmaClassification: String? = null,

    @ColumnInfo(name = "gemma_confidence")
    val gemmaConfidence: String? = null,

    @ColumnInfo(name = "gemma_rationale")
    val gemmaRationale: String? = null,

    @ColumnInfo(name = "ai_analysis_status", defaultValue = "'NOT_STARTED'")
    val aiAnalysisStatus: String = "NOT_STARTED",

    @ColumnInfo(name = "processing_state", defaultValue = "'COMPLETED'")
    val processingState: String = "PENDING",

    @ColumnInfo(name = "final_classification", defaultValue = "'SAFE'")
    val finalClassification: String = "SAFE",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
