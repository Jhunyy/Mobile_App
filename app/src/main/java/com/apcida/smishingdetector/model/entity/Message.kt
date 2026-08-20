package com.apcida.smishingdetector.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class Message(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "message_id")
    val messageId: Long = 0,

    @ColumnInfo(name = "sender_hash")
    val senderHash: String? = null,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "content_hash")
    val contentHash: String? = null,

    @ColumnInfo(name = "received_at")
    val receivedAt: Long,

    @ColumnInfo(name = "risk_score")
    val riskScore: Float = 0f,

    @ColumnInfo(name = "risk_level")
    val riskLevel: String = "SAFE",

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

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
