package com.trackr.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * iOS：Application Support/trackr.db，用 BundledSQLiteDriver。iOS 工程第 35 步接入。
 */
@OptIn(ExperimentalForeignApi::class)
fun createTrackrDatabase(): TrackrDatabase {
    val dir = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null
    )
    val path = requireNotNull(dir?.path) { "Application Support 目录不可用" } + "/" + TrackrDatabase.FILE_NAME
    return Room.databaseBuilder<TrackrDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .buildTrackrDatabase()
}

/** 测试用内存库 */
fun createInMemoryTrackrDatabase(): TrackrDatabase =
    Room.inMemoryDatabaseBuilder<TrackrDatabase>()
        .setDriver(BundledSQLiteDriver())
        .buildTrackrDatabase()
