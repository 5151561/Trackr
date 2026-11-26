package com.trackr.core.database

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** `@RunWith` 会被子类继承，commonTest 里的测试类因此跑在 Robolectric 上 */
@RunWith(RobolectricTestRunner::class)
actual abstract class PlatformDatabaseTest actual constructor() {
    actual fun createTestDatabase(): TrackrDatabase =
        createInMemoryTrackrDatabase(RuntimeEnvironment.getApplication())
}
