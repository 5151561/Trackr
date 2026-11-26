package com.trackr.core.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 首页当前用的坐标（首页 `currentLat` / `currentLng`）：GPS，或人在外地时换成的已选城市中心点。
 *
 * 第 17 步搜索从 HomeViewModel 拆出去后，搜索仍按这个坐标带距离参考（和拆分前一样）；
 * 只由首页写，进程内一份（Koin `single`）。和 [UserLocationStore] 不同：那个只存真实 GPS。
 */
class HomeCoordinates {
    private val _value = MutableStateFlow<Pair<Double, Double>?>(null)
    val value: StateFlow<Pair<Double, Double>?> = _value.asStateFlow()

    fun update(lat: Double, lng: Double) {
        _value.value = lat to lng
    }
}
