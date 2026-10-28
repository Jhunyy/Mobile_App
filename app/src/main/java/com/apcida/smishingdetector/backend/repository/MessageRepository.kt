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

    suspend fun claimRemoteAnalysis(messageId: Long, generation: Long): Boolean =
        messageDao.claimRemoteAnalysis(messageId, generation) == 1

    suspend fun completeRemoteAnalysis(messageId: Long, generation: Long,
        classification: String, confidence: String, rationale: String, detectionState: String): Boolean =
        messageDao.completeRemoteAnalysis(messageId, generation, classification,
            confidence, rationale, detectionState) == 1

    suspend fun getQueuedRemoteMessageIds(generation: Long, grantedAt: Long): List<Long> =
        messageDao.getQueuedRemoteMessageIds(generation, grantedAt)

    suspend fun failPendingRemoteAnalysis(messageId: Long, generation: Long): Boolean =
        messageDao.failPendingRemoteAnalysis(messageId, generation) == 1

    suspend fun disablePendingRemoteAnalysis(messageId: Long, generation: Long): Boolean =
        messageDao.disablePendingRemoteAnalysis(messageId, generation) == 1

    suspend fun failClaimedRemoteAnalysis(messageId: Long, generation: Long): Boolean =
        messageDao.failClaimedRemoteAnalysis(messageId, generation) == 1

    suspend fun disableClaimedRemoteAnalysis(messageId: Long, generation: Long): Boolean =
        messageDao.disableClaimedRemoteAnalysis(messageId, generation) == 1

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

    suspend fun getMessageBySourceSmsId(sourceSmsId: Long): Message? =
        messageDao.getMessageBySourceSmsId(sourceSmsId)

    suspend fun bindSourceSmsId(messageId: Long, sourceSmsId: Long) =
        messageDao.bindSourceSmsId(messageId, sourceSmsId)

    fun observeMessageById(messageId: Long): Flow<Message?> {
        return messageDao.observeMessageById(messageId)
    }

    suspend fun getPreviousMessages(
        senderHash: String?,
        receivedAt: Long,
        messageId: Long,
        windowMillis: Long,
        limit: Int
    ): List<Message> = if (senderHash == null) emptyList() else messageDao.getPreviousMessages(
        senderHash, receivedAt - windowMillis, receivedAt, messageId, limit
    )

    suspend fun getPreviousRiskMessage(
        senderHash: String?,
        receivedAt: Long,
        messageId: Long,
        windowMillis: Long
    ): Message? = if (senderHash == null) null else messageDao.getPreviousRiskMessage(
        senderHash, receivedAt - windowMillis, receivedAt, messageId
    )

    suspend fun getLaterMessages(
        senderHash: String?,
        receivedAt: Long,
        messageId: Long,
        windowMillis: Long
    ): List<Message> = if (senderHash == null) emptyList() else messageDao.getLaterMessages(
        senderHash, receivedAt, messageId, receivedAt + windowMillis
    )

    suspend fun applyHistoryWarning(messageId: Long, total: Int, historyPoints: Int) {
        messageDao.applyHistoryWarning(messageId, total, historyPoints)
    }

    suspend fun findRecentDuplicate(
        senderHash: String,
        contentHash: String,
        notBefore: Long,
        receivedAt: Long
    ): Message? {
        return messageDao.findRecentDuplicate(senderHash, contentHash, notBefore, receivedAt)
    }

    suspend fun findMatchingStoredSms(
        senderHash: String,
        contentHash: String,
        receivedAt: Long
    ): Message? = messageDao.findMatchingStoredSms(
        senderHash, contentHash,
        receivedAt - com.apcida.smishingdetector.backend.detection.DuplicatePolicy.WINDOW_MILLIS,
        receivedAt + com.apcida.smishingdetector.backend.detection.DuplicatePolicy.WINDOW_MILLIS,
        receivedAt
    )

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
