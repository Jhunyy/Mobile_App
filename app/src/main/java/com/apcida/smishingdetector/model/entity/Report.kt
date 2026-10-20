package com.apcida.smishingdetector.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reports",
    foreignKeys = [
        ForeignKey(
            entity = Message::class,
            parentColumns = ["message_id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["message_id"])
    ]
)
data class Report(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "report_id")
    val reportId: Long = 0,

    @ColumnInfo(name = "message_id")
    val messageId: Long?,

    @ColumnInfo(name = "client_report_id")
    val clientReportId: String? = null,

    @ColumnInfo(name = "report_type")
    val reportType: String,

    @ColumnInfo(name = "remarks")
    val remarks: String? = null,

    @ColumnInfo(name = "risk_score_snapshot")
    val riskScoreSnapshot: Float? = null,

    @ColumnInfo(name = "gemma_classification_snapshot")
    val gemmaClassificationSnapshot: String? = null,

    @ColumnInfo(name = "gemma_rationale_snapshot")
    val gemmaRationaleSnapshot: String? = null,

    @ColumnInfo(name = "gemma_confidence_snapshot")
    val gemmaConfidenceSnapshot: String? = null,

    @ColumnInfo(name = "final_verdict_snapshot")
    val finalVerdictSnapshot: String? = null,

    @ColumnInfo(name = "rule_verdict_snapshot")
    val ruleVerdictSnapshot: String? = null,

    @ColumnInfo(name = "processing_state_snapshot")
    val processingStateSnapshot: String? = null,

    @ColumnInfo(name = "ai_status_snapshot")
    val aiStatusSnapshot: String? = null,

    @ColumnInfo(name = "matched_indicators_snapshot")
    val matchedIndicatorsSnapshot: String? = null,

    @ColumnInfo(name = "sms_text_snapshot")
    val smsTextSnapshot: String? = null,

    @ColumnInfo(name = "app_version_snapshot")
    val appVersionSnapshot: String? = null,

    @ColumnInfo(name = "model_version_snapshot")
    val modelVersionSnapshot: String? = null,

    @ColumnInfo(name = "reported_at")
    val reportedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_sent")
    val isSent: Boolean = false
)
