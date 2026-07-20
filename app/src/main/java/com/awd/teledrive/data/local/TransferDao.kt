package com.awd.teledrive.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY createdAt DESC")
    fun getAllTransfersFlow(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE remoteUniqueId = :uniqueId LIMIT 1")
    suspend fun getTransferByUniqueId(uniqueId: String): TransferEntity?

    @Query("SELECT * FROM transfers WHERE fileId = :fileId LIMIT 1")
    suspend fun getTransferByFileId(fileId: Int): TransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(transfer: TransferEntity)

    @Query("DELETE FROM transfers WHERE remoteUniqueId = :uniqueId")
    suspend fun deleteTransfer(uniqueId: String)

    @Query("DELETE FROM transfers WHERE status = 'Selesai' OR status = 'Dibatalkan' OR status LIKE 'Gagal%'")
    suspend fun clearCompleted()

    @Query("UPDATE transfers SET progress = :progress, status = :status, downloadedSize = :downloadedSize WHERE remoteUniqueId = :uniqueId")
    suspend fun updateProgress(uniqueId: String, progress: Float, status: String, downloadedSize: Long)

    @Query("UPDATE transfers SET remoteUniqueId = :newUniqueId WHERE remoteUniqueId = :oldUniqueId")
    suspend fun updateUniqueId(oldUniqueId: String, newUniqueId: String)
}
