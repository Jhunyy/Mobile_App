package com.apcida.smishingdetector.backend.repository

import com.apcida.smishingdetector.backend.database.dao.ReportDao
import com.apcida.smishingdetector.model.entity.Report
import kotlinx.coroutines.flow.Flow

class ReportRepository(private val reportDao: ReportDao) {

    /**
     * Inserts a new report into the database.
     * Returns the generated report ID.
     */
    suspend fun insertReport(report: Report): Long {
        return reportDao.insertReport(report)
    }

    /**
     * Updates an existing report.
     * Primarily used to mark is_sent = true
     * after successful server transmission.
     */
    suspend fun updateReport(report: Report) {
        reportDao.updateReport(report)
    }

    /**
     * Returns a Flow of all reports ordered by newest first.
     */
    fun getAllReports(): Flow<List<Report>> {
        return reportDao.getAllReports()
    }

    /**
     * Returns all reports that have not yet been
     * transmitted to the remote server.
     * Used by ReportUploadManager.
     */
    suspend fun getUnsentReports(): List<Report> {
        return reportDao.getUnsentReports()
    }

    /**
     * Returns all reports linked to a specific message.
     */
    suspend fun getReportsForMessage(messageId: Long): List<Report> {
        return reportDao.getReportsForMessage(messageId)
    }

    /**
     * Marks a specific report as successfully sent.
     */
    suspend fun markReportAsSent(reportId: Long) {
        reportDao.markReportAsSent(reportId)
    }

    /**
     * Returns the total count of submitted reports.
     */
    suspend fun getReportCount(): Int {
        return reportDao.getReportCount()
    }

    /**
     * Returns the count of reports not yet sent to the server.
     */
    suspend fun getUnsentReportCount(): Int {
        return reportDao.getUnsentReportCount()
    }

    /**
     * Deletes all reports.
     * Used for testing and data reset.
     */
    suspend fun deleteAllReports() {
        reportDao.deleteAllReports()
    }
}