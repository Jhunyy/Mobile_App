package com.apcida.smishingdetector.backend.repository

import com.apcida.smishingdetector.backend.database.dao.MessageDao
import com.apcida.smishingdetector.model.entity.Message
import kotlinx.coroutines.flow.Flow

class MessageRepository(private val messageDao: MessageDao) {

    /**
     * Inserts a new message into the database.
     * Returns the generated message ID.
     */
    suspend fun insertMessage(message: Message): Long {
        return messageDao.insertMessage(message)
    }

    /**
     * Updates an existing message.
     * Used after Gemma validation to save classification results.
     */
    suspend fun updateMessage(message: Message) {
        messageDao.updateMessage(message)
    }

    /**
     * Deletes a specific message.
     */
    suspend fun deleteMessage(message: Message) {
        messageDao.deleteMessage(message)
    }

    /**
     * Returns a Flow of all messages ordered by newest first.
     * Flow automatically updates the UI when data changes.
     */
    fun getAllMessages(): Flow<List<Message>> {
        return messageDao.getAllMessages()
    }

    /**
     * Returns a Flow of only flagged/scam messages.
     */
    fun getFlaggedMessages(): Flow<List<Message>> {
        return messageDao.getFlaggedMessages()
    }

    /**
     * Returns a single message by its ID.
     * Returns null if not found.
     */
    suspend fun getMessageById(messageId: Long): Message? {
        return messageDao.getMessageById(messageId)
    }

    fun observeMessageById(messageId: Long): Flow<Message?> {
        return messageDao.observeMessageById(messageId)
    }

    suspend fun findRecentDuplicate(
        senderHash: String,
        contentHash: String,
        notBefore: Long,
        receivedAt: Long
    ): Message? {
        return messageDao.findRecentDuplicate(senderHash, contentHash, notBefore, receivedAt)
    }

    /**
     * Deletes all messages from the database.
     * Used for testing and data reset.
     */
    suspend fun deleteAllMessages() {
        messageDao.deleteAllMessages()
    }

    /**
     * Returns the total count of all messages.
     */
    suspend fun getMessageCount(): Int {
        return messageDao.getMessageCount()
    }

    /**
     * Returns the count of scam/flagged messages.
     */
    suspend fun getScamMessageCount(): Int {
        return messageDao.getScamMessageCount()
    }
}
