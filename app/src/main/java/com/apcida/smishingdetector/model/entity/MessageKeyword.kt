package com.apcida.smishingdetector.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_keywords",
    foreignKeys = [
        ForeignKey(
            entity = Message::class,
            parentColumns = ["message_id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Keyword::class,
            parentColumns = ["keyword_id"],
            childColumns = ["keyword_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["message_id"]),
        Index(value = ["keyword_id"])
    ]
)
data class MessageKeyword(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "message_id")
    val messageId: Long,

    @ColumnInfo(name = "keyword_id")
    val keywordId: Long,

    @ColumnInfo(name = "matched_text")
    val matchedText: String? = null,

    @ColumnInfo(name = "match_position")
    val matchPosition: Int? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
