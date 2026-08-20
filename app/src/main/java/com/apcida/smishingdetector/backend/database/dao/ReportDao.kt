package com.apcida.smishingdetector.backend.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.apcida.smishingdetector.model.entity.Report
import kotlinx.coroutines.flow.Flow

@Dao
interface ReportDao {

    // Insert a new report
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReport(report: Report): Long

    // Update report — used to mark is_sent = true after successful upload
    @Update
    suspend fun updateReport(report: Report)

    // Get all reports ordered by newest first
    @Query("SELECT * FROM reports ORDER BY reported_at DESC")
    fun getAllReports(): Flow<List<Report>>

    // Get all unsent reports — used by ReportUploadManager
    @Query("SELECT * FROM reports WHERE is_sent = 0")
    suspend fun getUnsentReports(): List<Report>

    // Get reports linked to a specific message
    @Query("SELECT * FROM reports WHERE message_id = :messageId")
    suspend fun getReportsForMessage(messageId: Long): List<Report>

    // Mark a report as sent
    @Query("UPDATE reports SET is_sent = 1 WHERE report_id = :reportId")
    suspend fun markReportAsSent(reportId: Long)

    // Get total count of submitted reports
    @Query("SELECT COUNT(*) FROM reports")
    suspend fun getReportCount(): Int

    // Get count of unsent reports
    @Query("SELECT COUNT(*) FROM reports WHERE is_sent = 0")
    suspend fun getUnsentReportCount(): Int

    // Delete all reports — used in testing/reset
    @Query("DELETE FROM reports")
    suspend fun deleteAllReports()
}