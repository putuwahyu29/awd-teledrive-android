package com.awd.teledrive.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.awd.teledrive.R
import java.io.File

object FileSharingHelper {
    fun shareFile(context: Context, filePath: String, mimeType: String) {
        val file = File(filePath)
        if (!file.exists()) return

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
    }

    fun openFileExternally(context: Context, filePath: String, mimeType: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) return

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_with_other)))
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(context, context.getString(R.string.err_open_app), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
