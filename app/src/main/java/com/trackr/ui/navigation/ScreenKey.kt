package com.trackr.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.trackr.domain.model.DataSourceType
import kotlinx.serialization.Serializable

@Serializable
sealed interface ScreenKey : NavKey {
    @Serializable
    data object Home : ScreenKey

    @Serializable
    data object FavoritesGroup : ScreenKey

    @Serializable
    data object Search : ScreenKey

    @Serializable
    data class LineDetail(
        val cityId: String,
        val lineId: String,
        val targetOrder: Int = 0,
        val stopId: String = "",
        val source: DataSourceType = DataSourceType.CHELAILE,
        val lineName: String = ""
    ) : ScreenKey

    @Serializable
    data class StopDetail(
        val cityId: String,
        val stopId: String,
        val stopName: String = "",
        val physicalStId: String = "",
        val namesakeStId: String = "",
        val lat: Double = 0.0,
        val lng: Double = 0.0
    ) : ScreenKey

    @Serializable
    data object More : ScreenKey

    @Serializable
    data object About : ScreenKey
}

@Serializable
data class PendingDeepLink(
    val cityId: String,
    val lineId: String,
    val targetOrder: Int = 0,
    val stopId: String = "",
    val sourceName: String = "",
    val lineName: String = ""
)
