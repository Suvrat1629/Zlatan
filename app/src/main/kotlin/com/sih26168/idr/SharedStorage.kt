package com.sih26168.idr

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * Writes telemetry to the phone's shared Documents/IDR folder rather than app-private storage,
 * so a tester can open the files in the Files app or copy them off over USB without adb.
 *
 * Android 10 and later go through MediaStore, which needs no storage permission. Older versions
 * write the public Documents directory directly, which does.
 *
 * One consequence worth knowing: a MediaStore entry cannot be reopened for append, so a session
 * is one file. If the app is killed mid-drive the file ends where it ended — the bytes already
 * flushed survive.
 */
object SharedStorage {

    private const val FOLDER = "Documents/IDR"

    fun openForWrite(context: Context, name: String, mime: String): OutputStream? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mediaStore(context, name, mime)
        else legacy(name)

    private fun mediaStore(context: Context, name: String, mime: String): OutputStream? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values) ?: return null
        return context.contentResolver.openOutputStream(uri)
    }

    private fun legacy(name: String): OutputStream? = try {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "IDR"
        ).apply { mkdirs() }
        File(dir, name).outputStream()
    } catch (e: Exception) {
        null
    }
}
