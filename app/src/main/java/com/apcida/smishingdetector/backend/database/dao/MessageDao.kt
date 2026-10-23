package com.apcida.smishingdetector.backend.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.apcida.smishingdetector.model.entity.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    // Insert a new message — ignore if duplicate content_hash exists
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: Message): Long

    // Update an existing message (used after Gemma validation)
    @Update
    suspend fun updateMessage(message: Message)

    // Delete a single message
    @Delete
    suspend fun deleteMessage(message: Message)

    // Get all messages ordered by newest first — returns Flow for live UI updates
    @Query("SELECT * FROM messages ORDER BY received_at DESC")
    fun getAllMessages(): Flow<List<Message>>

    // Get only flagged/scam messages
    @Query("SELECT * FROM messages WHERE is_flagged = 1 ORDER BY received_at DESC")
    fun getFlaggedMessages(): Flow<List<Message>>

    // Get a single message by ID
    @Query("SELECT * FROM messages WHERE message_id = :messageId")
    suspend fun getMessageById(messageId: Long): Message?

    @Query("SELECT * FROM messages WHERE message_id = :messageId")
    fun observeMessageById(messageId: Long): Flow<Message?>

    @Query("SELECT message_id FROM messages WHERE processing_state IN ('AI_QUEUED', 'AI_ANALYZING') AND created_at < :before")
    suspend fun getStaleAiMessageIds(before: Long): List<Long>

    @Query(
        """
        SELECT * FROM messages
        WHERE sender_hash = :senderHash
          AND received_at >= :notBefore
          AND (received_at < :receivedAt OR (received_at = :receivedAt AND message_id < :messageId))
        ORDER BY received_at DESC, message_id DESC
        LIMIT :limit
        """
    )
    suspend fun getPreviousMessages(
        senderHash: String,
        notBefore: Long,
        receivedAt: Long,
        messageId: Long,
        limit: Int
    ): List<Message>

    @Query(
        """
        SELECT * FROM messages
        WHERE sender_hash = :senderHash
          AND received_at >= :notBefore
          AND (received_at < :receivedAt OR (received_at = :receivedAt AND message_id < :messageId))
          AND ai_analysis_status = 'AVAILABLE'
          AND gemma_classification = 'SCAM'
          AND ((final_classification = 'SCAM' AND gemma_confidence IN ('HIGH', 'MEDIUM'))
               OR (final_classification != 'SAFE' AND gemma_confidence = 'LOW' AND ai_risk_points > 0))
        ORDER BY CASE
            WHEN final_classification = 'SCAM' AND gemma_confidence IN ('HIGH', 'MEDIUM') THEN 0
            ELSE 1
        END, received_at DESC
        LIMIT 1
        """
    )
    suspend fun getPreviousRiskMessage(
        senderHash: String,
        notBefore: Long,
        receivedAt: Long,
        messageId: Long
    ): Message?

    @Query(
        """
        SELECT * FROM messages
        WHERE sender_hash = :senderHash
          AND (received_at > :receivedAt OR (received_at = :receivedAt AND message_id > :messageId))
          AND received_at <= :notAfter
        ORDER BY received_at ASC, message_id ASC
        """
    )
    suspend fun getLaterMessages(
        senderHash: String,
        receivedAt: Long,
        messageId: Long,
        notAfter: Long
    ): List<Message>

    @Query(
        """
        UPDATE messages
        SET combined_risk_score = :total,
            history_risk_points = :historyPoints,
            risk_level = CASE WHEN final_classification = 'SAFE' AND :total >= 25 THEN 'SUSPICIOUS' ELSE risk_level END,
            final_classification = CASE WHEN final_classification = 'SAFE' AND :total >= 25 THEN 'SUSPICIOUS' ELSE final_classification END,
            is_flagged = CASE WHEN :total >= 25 THEN 1 ELSE is_flagged END
        WHERE message_id = :messageId
        """
    )
    suspend fun applyHistoryWarning(messageId: Long, total: Int, historyPoints: Int)

    // Suppress duplicate broadcasts only for the same sender/content in a
    // bounded window. A legitimate repeated SMS outside the window is kept.
    @Query(
        """
        SELECT * FROM messages
        WHERE sender_hash = :senderHash
          AND content_hash = :contentHash
          AND received_at >= :notBefore
          AND received_at <= :receivedAt
        ORDER BY received_at DESC
        LIMIT 1
        """
    )
    suspend fun findRecentDuplicate(
        senderHash: String,
        contentHash: String,
        notBefore: Long,
        receivedAt: Long
    ): Message?

    // Delete all messages — used in testing/reset
    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    // Get total count of messages
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Int

    // Get count of scam messages
    @Query("SELECT COUNT(*) FROM messages WHERE is_flagged = 1")
    suspend fun getScamMessageCount(): Int
}
