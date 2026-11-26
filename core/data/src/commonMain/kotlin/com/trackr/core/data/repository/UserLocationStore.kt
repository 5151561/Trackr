package com.trackr.core.data.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * 首页定位写入的最近坐标（WGS-84），站点详情 / 搜索 / 规划页「我的位置」没传坐标时用。
 *
 * 第 17 步过渡用：原来是 `:androidApp` 里 `ITransitRepository` 的两个成员，ViewModel 搬进功能模块后看不到它，
 * 先抽成这个小接口（Android 由 `ITransitRepository` 适配）。值和原来一样只由首页定位写入，
 * 不改用 [LocationRepository.latest]（那个追踪时也会更新，含义更宽）。
 */
interface UserLocationStore {
    val userLocation: StateFlow<Pair<Double, Double>?>

    fun updateUserLocation(lat: Double, lng: Double)
}
