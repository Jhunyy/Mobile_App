package com.apcida.smishingdetector.backend.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.apcida.smishingdetector.model.entity.MessageKeyword

@Dao
interface MessageKeywordDao {

    // Insert a single message-keyword match
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageKeyword(messageKeyword: MessageKeyword): Long

    // Insert multiple matches at once
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllMessageKeywords(matches: List<MessageKeyword>)

    // Get all keyword matches for a specific message
    @Query("SELECT * FROM message_keywords WHERE message_id = :messageId")
    suspend fun getMatchesForMessage(messageId: Long): List<MessageKeyword>

    // Get all messages that matched a specific keyword
    @Query("SELECT * FROM message_keywords WHERE keyword_id = :keywordId")
    suspend fun getMessagesForKeyword(keywordId: Long): List<MessageKeyword>

    // Delete all matches for a message (used when message is deleted)
    @Query("DELETE FROM message_keywords WHERE message_id = :messageId")
    suspend fun deleteMatchesForMessage(messageId: Long)

    // Get total match count — useful for analytics
    @Query("SELECT COUNT(*) FROM message_keywords")
    suspend fun getTotalMatchCount(): Int
}