package com.awd.teledrive.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface DriveDao {
    @Query("SELECT * FROM drive_items WHERE parentChatId = :chatId AND (splitGroupId IS NULL OR partIndex = 0) AND virtualParentId = :virtualParentId ORDER BY createdAt DESC")
    fun getItemsFlow(chatId: Long, virtualParentId: String): Flow<List<DriveItemEntity>>

    @Query("SELECT * FROM drive_items WHERE parentChatId = :chatId AND (splitGroupId IS NULL OR partIndex = 0) AND virtualParentId = :virtualParentId")
    fun getItemsByVirtualParent(chatId: Long, virtualParentId: String): Flow<List<DriveItemEntity>>

    @Query("SELECT * FROM drive_items WHERE isStarred = 1 AND (splitGroupId IS NULL OR partIndex = 0) ORDER BY createdAt DESC")
    fun getStarredItems(): Flow<List<DriveItemEntity>>

    @Query("UPDATE drive_items SET isStarred = :isStarred WHERE id = :id AND parentChatId = :chatId")
    suspend fun updateStarred(id: Long, chatId: Long, isStarred: Boolean)

    @Query("UPDATE drive_items SET name = :newName WHERE id = :id AND parentChatId = :chatId")
    suspend fun renameItem(id: Long, chatId: Long, newName: String)

    @Query("SELECT * FROM drive_items WHERE id = :id AND parentChatId = :chatId LIMIT 1")
    suspend fun getItemById(id: Long, chatId: Long): DriveItemEntity?

    @Query("SELECT * FROM drive_items WHERE remoteUniqueId = :uniqueId LIMIT 1")
    suspend fun getItemByUniqueId(uniqueId: String): DriveItemEntity?

    @Query("SELECT * FROM drive_items WHERE virtualId = :vId LIMIT 1")
    suspend fun getVirtualFolderById(vId: String): DriveItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<DriveItemEntity>)

    @Transaction
    suspend fun refreshChatItems(chatId: Long, items: List<DriveItemEntity>, preserveFolders: Boolean = false) {
        val newItemIds = items.map { it.id }.toSet()
        val existingItems = getItemsSync(chatId)
        val starredItemIds = existingItems.filter { it.isStarred }.map { it.id }.toSet()
        
        val updatedItems = items.map { item ->
            if (item.id in starredItemIds) {
                item.copy(isStarred = true)
            } else {
                item
            }
        }

        val toDelete = existingItems.filter { item ->
            item.id !in newItemIds && (!preserveFolders || !item.isFolder) && !item.isVirtual
        }.map { it.id }
        
        if (toDelete.isNotEmpty()) {
            deleteItemsByIds(chatId, toDelete)
        }
        insertItems(updatedItems)
    }

    @Transaction
    suspend fun insertItemsPreservingStarred(items: List<DriveItemEntity>) {
        val ids = items.map { it.id }
        // Note: This only works if parentChatId is also considered or if IDs are unique enough.
        // The table has primaryKeys = ["id", "parentChatId"]
        
        val updatedItems = items.map { item ->
            val existing = getItemById(item.id, item.parentChatId)
            if (existing?.isStarred == true) {
                item.copy(isStarred = true)
            } else {
                item
            }
        }
        insertItems(updatedItems)
    }

    @Query("SELECT * FROM drive_items WHERE parentChatId = :chatId")
    suspend fun getItemsSync(chatId: Long): List<DriveItemEntity>

    @Query("SELECT * FROM drive_items WHERE virtualParentId = :virtualId")
    suspend fun getItemsByVirtualParentSync(virtualId: String): List<DriveItemEntity>

    @Query("DELETE FROM drive_items WHERE parentChatId = :chatId AND id IN (:ids)")
    suspend fun deleteItemsByIds(chatId: Long, ids: List<Long>)

    @Query("UPDATE drive_items SET localPath = :path WHERE remoteUniqueId = :uniqueId")
    suspend fun updateLocalPathByUniqueId(uniqueId: String, path: String)

    @Query("UPDATE drive_items SET thumbnailPath = :path WHERE remoteUniqueId = :uniqueId OR thumbnailRemoteUniqueId = :uniqueId")
    suspend fun updateThumbnailPathByUniqueId(uniqueId: String, path: String)

    @Query("UPDATE drive_items SET localPath = :path WHERE telegramFileId = :fileId")
    suspend fun updateLocalPath(fileId: Int, path: String)

    @Query("UPDATE drive_items SET localPath = NULL")
    suspend fun clearAllLocalPaths()

    @Query("UPDATE drive_items SET thumbnailPath = NULL")
    suspend fun clearAllThumbnailPaths()

    @Query("DELETE FROM drive_items WHERE parentChatId = :chatId")
    suspend fun deleteItemsByChat(chatId: Long)

    @Query("DELETE FROM drive_items WHERE id = :id AND parentChatId = :chatId")
    suspend fun deleteItemCompletely(id: Long, chatId: Long)

    @Query("DELETE FROM drive_items WHERE parentChatId = :chatId AND id NOT IN (:ids) AND isFolder = 0 AND isVirtual = 0")
    suspend fun deleteFilesByChatNotInList(chatId: Long, ids: List<Long>)

    @Query("DELETE FROM drive_items WHERE id < 0")
    suspend fun deletePendingItems()

    @Query("SELECT * FROM drive_items WHERE name LIKE '%' || :query || '%' AND (splitGroupId IS NULL OR partIndex = 0) ORDER BY createdAt DESC")
    fun searchGlobal(query: String): Flow<List<DriveItemEntity>>

    @Query("SELECT * FROM drive_items WHERE isFolder = 0 AND (splitGroupId IS NULL OR partIndex = 0) ORDER BY createdAt DESC")
    fun getAllFiles(): Flow<List<DriveItemEntity>>

    @Query("SELECT SUM(size) FROM drive_items WHERE isFolder = 0")
    fun getTotalSize(): Flow<Long?>

    @Query("SELECT * FROM drive_items WHERE splitGroupId = :groupId ORDER BY partIndex ASC")
    suspend fun getSplitFileParts(groupId: String): List<DriveItemEntity>

    data class FileTypeStat(
        val category: String,
        val count: Int,
        val totalSize: Long
    )

    @Query("""
        SELECT 
            CASE 
                WHEN mimeType LIKE 'image/%' THEN 'IMAGE'
                WHEN mimeType LIKE 'video/%' THEN 'VIDEO'
                WHEN mimeType LIKE 'audio/%' THEN 'AUDIO'
                WHEN mimeType LIKE 'application/pdf' 
                     OR mimeType LIKE 'text/%' 
                     OR mimeType LIKE '%msword%' 
                     OR mimeType LIKE '%document%' 
                     OR mimeType LIKE '%sheet%' 
                     OR mimeType LIKE '%presentation%'
                     OR mimeType LIKE '%zip%'
                     OR mimeType LIKE '%rar%'
                     OR mimeType LIKE '%archive%'
                     OR mimeType LIKE '%7z%'
                THEN 'DOCUMENT'
                ELSE 'OTHER'
            END as category,
            COUNT(*) as count,
            SUM(size) as totalSize
        FROM drive_items 
        WHERE isFolder = 0 
        GROUP BY category
    """)
    fun getCloudFileTypeStats(): Flow<List<FileTypeStat>>
}
