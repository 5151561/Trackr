package com.trackr.core.datastore

import android.content.Context
import okio.FileSystem

actual fun systemFileSystem(): FileSystem = FileSystem.SYSTEM

/** Android 上的 DataStore 文件路径：`files/datastore/<文件名>`（和 androidx 的 preferencesDataStoreFile 一致） */
fun Context.trackrDataStorePath(fileName: String): String =
    (applicationContext ?: this).filesDir.resolve(TrackrDataStoreFiles.DIRECTORY).resolve(fileName).absolutePath
