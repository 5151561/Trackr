package com.trackr.core.database

/**
 * DAO 测试的基类：每个平台给一个内存库。
 * Android 主机测试跑在 Robolectric 上（和真机一样用系统 SQLite）；iOS 模拟器上用 BundledSQLiteDriver。
 */
expect abstract class PlatformDatabaseTest() {
    fun createTestDatabase(): TrackrDatabase
}
