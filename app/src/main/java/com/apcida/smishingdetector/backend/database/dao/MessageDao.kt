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

    // Check for duplicate message by content hash
    @Query("SELECT * FROM messages WHERE content_hash = :hash LIMIT 1")
    suspend fun getMessageByHash(hash: String): Message?

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