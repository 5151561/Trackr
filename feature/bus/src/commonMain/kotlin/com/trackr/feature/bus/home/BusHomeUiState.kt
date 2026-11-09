package com.trackr.feature.bus.home

import com.trackr.core.model.DataSourceType
import com.trackr.core.model.EngineMode
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TransitCity
import com.trackr.core.model.isLenzOnlyCity
import com.trackr.ui.components.arrival.ArrivalUi

/**
 * 公交首页的状态（v3「公交」页）。
 *
 * 上半部分是数据（ViewModel 自己用、测试断言用），下半部分 [trackingCard]、[favoriteRows]、[nearbyCards]
 * 是给页面画的扁平 UI 模型（重构计划 §4.1）：每次数据变了由 [BusHomeMapper] 重新算，没变的行沿用旧实例。
 */
data class BusHomeUiState(
    val currentCity: TransitCity = DEFAULT_CITY,
    val selectedProvince: String = "直辖市",
    val locatedCity: TransitCity? = null,
    val cities: List<TransitCity> = emptyList(),
    val citySearchQuery: String = "",
    val isCityPickerOpen: Boolean = false,
    val currentLat: Double = 31.2304,
    val currentLng: Double = 121.4737,
    val isLocating: Boolean = false,
    val locationError: String? = null,
    val nearbyStops: List<NearbyStop> = emptyList(),
    val isLoadingNearby: Boolean = false,
    /** 收藏的车（站 + 线），小组件 / 快捷方式也用这份 */
    val favorites: List<FavoriteStationLine> = emptyList(),
    /** 收藏的车的实时到站，key 是 [FavoriteStationLine.id] */
    val favoritesRealtime: Map<String, NearbyStop> = emptyMap(),
    /** 收藏合一列表（车和路线排在一起，第 14 步） */
    val favoriteItems: List<FavoriteItem> = emptyList(),
    val favoriteGroups: List<FavoriteGroup> = emptyList(),
    /** 分组 chips 选中的分组；null =「全部」 */
    val selectedGroupId: String? = null,
    /** 当前这一程的追踪状态（单线路、路线、一起等排第一的那条） */
    val activeTracking: TrackingSessionState = TrackingSessionState(),
    /** 整个追踪会话（一起等的几条线在这里）；没在追踪时为 null */
    val trackingSession: TrackingSession? = null,
    val isRefreshing: Boolean = false,
    val engineMode: EngineMode = EngineMode.CHELAILE,
    /** 当前连不上数据源的城市名（掌上出行一城一服务器，常在线的没几台） */
    val unreachableCityNames: Set<String> = emptySet(),
    /** 当前城市能整体列出来的线路（掌上出行县级城市）；别的数据源列不了，是空的 */
    val cityLines: List<CityLineUi> = emptyList(),
    val isLoadingCityLines: Boolean = false,
    /** 「每次询问」时点乘车码弹出的平台选择 */
    val isTransitCodeSheetOpen: Boolean = false,
    /** 右下角「乘车码」悬浮按钮显不显示（「我的」里可以关掉，用 NFC 的嫌它挡列表） */
    val showTransitCodeButton: Boolean = true,
    /** 一次性消息（重构计划 §2.1 UDF）：页面显示后回调 [BusHomeEvent.MessageShown] */
    val userMessages: List<UserMessage> = emptyList(),

    // ---- 给页面画的 UI 模型 ----
    val trackingCard: TrackingCardUi? = null,
    val favoriteRows: List<FavoriteRowUi> = emptyList(),
    val nearbyCards: List<NearbyStopUi> = emptyList()
) {
    val filteredCities: List<TransitCity>
        get() {
            // 本数据源支持的城市 + 朗斯独有的县级城市（如「范县」）；
            // 后者在任何模式下都要能看到，否则用户切到车来了模式就永远选不到它（选中后会自动切源）。
            val baseList = when (engineMode) {
                EngineMode.MYGOLBS -> cities.filter {
                    !it.mygolbsCityCode.isNullOrBlank() || it.isLenzOnlyCity
                }
                EngineMode.CHELAILE -> cities.filter {
                    it.cityId.isNotBlank() || it.isLenzOnlyCity
                }
                EngineMode.LENZ -> cities.filter {
                    !it.lenzServerIp.isNullOrBlank() || it.cityId.isNotBlank()
                }
            }
            if (citySearchQuery.isBlank()) return baseList
            val q = citySearchQuery.trim().lowercase()
            return baseList.filter {
                it.cityName.lowercase().contains(q) ||
                    it.pinyin.lowercase().contains(q) ||
                    // 搜上级地级市也能搜到下面的县（搜「濮阳」出范县 / 清丰 / 台前）
                    it.lenzRegion.orEmpty().lowercase().contains(q) ||
                    (it.province.ifBlank { ProvinceCityCatalog.inferProvince(it.cityName) }).lowercase().contains(q)
            }
        }

    /** 定位 / 下拉刷新 / 首次加载附近站中，任何一个在进行就显示刷新圈 */
    val isBusy: Boolean get() = isRefreshing || isLocating || isLoadingNearby

    companion object {
        val DEFAULT_CITY = TransitCity(
            cityId = "034",
            cityName = "上海",
            pinyin = "shanghai",
            supportSubway = true,
            isHot = true,
            province = "直辖市"
        )
    }
}

/** 一次性消息。[id] 用来回报「显示过了」 */
data class UserMessage(
    val id: Long,
    val text: String,
    /** 长辈版换一种说法（长辈首页没有下拉刷新）；null 时两版一样 */
    val elderText: String? = null
)

/** 打开线路页要的参数（和 `ScreenKey.LineDetail` 一一对应） */
data class LineTarget(
    val cityId: String,
    val lineId: String,
    val targetOrder: Int,
    val stopId: String,
    val source: DataSourceType,
    val lineName: String
)

/**
 * 首页「本城线路」里的一行（掌上出行县级城市直接列全部线路）。
 * 一条线路只占一行；正反两个方向都有的，起点终点之间用「⇄」表示，进详情可以换向。
 */
data class CityLineUi(
    val key: String,
    val lineName: String,
    val startStop: String,
    val endStop: String,
    /** 有对开方向（upDown 0/1 都有） */
    val isBidirectional: Boolean,
    val open: LineTarget
)

/** 打开站点页要的参数（和 `ScreenKey.StopDetail` 一一对应） */
data class StopTarget(
    val cityId: String,
    val stopId: String,
    val stopName: String,
    val physicalStId: String,
    val namesakeStId: String,
    val lat: Double,
    val lng: Double
)

/**
 * 首页顶上的追踪卡。
 *
 * @param title 第一行（「追踪 325 · 科技园站」「一起等 3 条 · 科技园站」「路线 家 → 公司 · 第 1 程，共 2 程」）
 * @param arrival 大字（最先到的那辆）
 * @param detail 大字下面一行（「325 先到 · 还有 2 站」）
 * @param otherLines 一起等的其他线，直接显示的几条
 * @param foldedLines 一起等条数多时折叠起来的，显示成「还有 N 条」
 * @param open 点卡片去哪个线路页
 * @param spoken「读给我听」读的整句（长辈版）；空串 = 不给读（已经在车上）
 */
data class TrackingCardUi(
    val title: String,
    val arrival: ArrivalUi,
    val detail: String,
    val otherLines: List<WatchLineUi> = emptyList(),
    val foldedLines: List<WatchLineUi> = emptyList(),
    val open: LineTarget,
    val spoken: String = ""
)

/** 一起等里的一条线 */
data class WatchLineUi(val key: String, val lineName: String, val arrival: ArrivalUi)

/** 收藏合一列表里的一行 */
sealed interface FavoriteRowUi {
    /** 合一列表的 key（`stop_x` / `route_x`，两种类型间不重复） */
    val key: String

    /**
     * 收藏的车：「325 · 科技园站上车 · → 科技园」+ 到站。
     * @param arrival 还没拿到实时数据时是「正在查车」
     * @param spoken「读给我听」读的整句（长辈版），如「325路，开往科技园，还有 2 站，大约 4 分钟到科技园站」
     */
    data class Stop(
        override val key: String,
        val lineName: String,
        val boardText: String,
        val direction: String,
        val arrival: ArrivalUi,
        val open: LineTarget,
        val spoken: String = ""
    ) : FavoriteRowUi

    /** 收藏的路线：「家 → 公司」+「325 › 19 · 换乘 1 次」 */
    data class Route(
        override val key: String,
        val routeId: String,
        val title: String,
        val linesText: String
    ) : FavoriteRowUi
}

/** 附近的一个站 */
data class NearbyStopUi(
    val key: String,
    val name: String,
    /** 「120 米 · 8 条线 · 地铁 1 号线」 */
    val meta: String,
    val lines: List<NearbyLineUi>,
    val open: StopTarget
)

/** 附近站里的一条线：「325 → 科技园 · 4 分钟」 */
data class NearbyLineUi(
    val key: String,
    val lineName: String,
    val direction: String,
    val arrival: ArrivalUi,
    val open: LineTarget
)
