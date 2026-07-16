package com.awd.teledrive.data.remote

import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance

@Singleton
class TelegramClient @Inject constructor() {
    private var client: Client? = null
    private val _updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 500)
    val updates = _updates.asSharedFlow()

    val fileUpdates = updates.filterIsInstance<TdApi.UpdateFile>()

    init {
        try {
            // Load the native library explicitly before creating the client
            System.loadLibrary("tdjni")
            Log.d("TelegramClient", "TDLib native library loaded successfully")
            
            // Initialize TDLib
            client = Client.create(
                { update ->
                    _updates.tryEmit(update)
                },
                null,
                null,
            )
        } catch (e: UnsatisfiedLinkError) {
            Log.e("TelegramClient", "Failed to load TDLib native library", e)
        } catch (e: Exception) {
            Log.e("TelegramClient", "Error creating TDLib client", e)
        }
    }

    fun send(function: TdApi.Function<out TdApi.Object>, resultHandler: Client.ResultHandler = Client.ResultHandler { }) {
        val currentClient = client
        if (currentClient != null) {
            currentClient.send(function, resultHandler)
        } else {
            Log.e("TelegramClient", "Cannot send request: client is null (initialization failed?)")
            resultHandler.onResult(TdApi.Error(500, "TDLib client not initialized"))
        }
    }
}
