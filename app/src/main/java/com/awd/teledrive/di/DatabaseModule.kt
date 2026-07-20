package com.awd.teledrive.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.awd.teledrive.data.local.DriveDao
import com.awd.teledrive.data.local.TeleDriveDatabase
import com.awd.teledrive.data.local.TransferDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TeleDriveDatabase {
        Log.d("DatabaseModule", "Initializing database WITHOUT encryption for troubleshooting...")
        
        return Room.databaseBuilder(
            context,
            TeleDriveDatabase::class.java,
            "teledrive_no_enc.db"
        )
        .fallbackToDestructiveMigration(true)
        .build()
    }

    @Provides
    fun provideDriveDao(database: TeleDriveDatabase): DriveDao {
        return database.driveDao()
    }

    @Provides
    fun provideTransferDao(database: TeleDriveDatabase): TransferDao {
        return database.transferDao()
    }
}
