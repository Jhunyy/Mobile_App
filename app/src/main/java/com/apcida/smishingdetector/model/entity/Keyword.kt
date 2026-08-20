package com.apcida.smishingdetector.model.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "keywords")
data class Keyword(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "keyword_id")
    val keywordId: Long = 0,

    @ColumnInfo(name = "pattern")
    val pattern: String,

    @ColumnInfo(name = "pattern_type")
    val patternType: String? = null,

    @ColumnInfo(name = "weight")
    val weight: Float = 0f,

    @ColumnInfo(name = "language")
    val language: String? = null,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long? = null
)
