package com.trackr.core.database

actual abstract class PlatformDatabaseTest actual constructor() {
    actual fun createTestDatabase(): TrackrDatabase = createInMemoryTrackrDatabase()
}
