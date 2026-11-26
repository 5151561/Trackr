package com.trackr.core.database

import android.content.Context
import androidx.room.Room

/**
 * Android：`databases/trackr.db`。不调 setDriver，用系统自带 SQLite（不打包 sqlite .so）。
 */
fun createTrackrDatabase(context: Context): TrackrDatabase {
    val appContext = context.applicationContext ?: context
    return Room.databaseBuilder<TrackrDatabase>(
        context = appContext,
        name = appContext.getDatabasePath(TrackrDatabase.FILE_NAME).absolutePath,
        // 直接用 Room 生成的构造器，不走反射找 TrackrDatabase_Impl（R8 收紧后也不会被裁掉）
        factory = { TrackrDatabaseConstructor.initialize() }
    ).buildTrackrDatabase()
}

/** 测试用内存库 */
fun createInMemoryTrackrDatabase(context: Context): TrackrDatabase =
    Room.inMemoryDatabaseBuilder<TrackrDatabase>(
        context = context.applicationContext ?: context,
        factory = { TrackrDatabaseConstructor.initialize() }
    )
        .buildTrackrDatabase()
