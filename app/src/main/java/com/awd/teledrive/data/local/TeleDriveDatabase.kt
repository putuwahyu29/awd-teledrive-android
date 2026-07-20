package com.awd.teledrive.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [DriveItemEntity::class, TransferEntity::class], version = 2, exportSchema = false)
abstract class TeleDriveDatabase : RoomDatabase() {
    abstract fun driveDao(): DriveDao
    abstract fun transferDao(): TransferDao
}
