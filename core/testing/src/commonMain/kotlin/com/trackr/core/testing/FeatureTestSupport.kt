package com.trackr.core.testing

import com.trackr.core.data.repository.UserLocationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/*
 * 第 17 步：ViewModel 测试搬进各功能模块的 commonTest 后要用的辅助。
 */

/** [UserLocationStore] 的内存实现：[updateUserLocation] 直接改值 */
class FakeUserLocationStore(initial: Pair<Double, Double>? = null) : UserLocationStore {
    private val _userLocation = MutableStateFlow(initial)
    override val userLocation: StateFlow<Pair<Double, Double>?> = _userLocation.asStateFlow()

    override fun updateUserLocation(lat: Double, lng: Double) {
        _userLocation.value = lat to lng
    }
}

/**
 * commonTest 里替代 JUnit 的 `MainDispatcherRule`：`@BeforeTest` 调 [install]，`@AfterTest` 调 [reset]。
 * `runTest(main.dispatcher)` 用同一个调度器，`viewModelScope` 里的 `delay` 才受 `advanceTimeBy` 控制。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherHolder(val dispatcher: TestDispatcher = StandardTestDispatcher()) {
    fun install() = Dispatchers.setMain(dispatcher)
    fun reset() = Dispatchers.resetMain()
}
