package com.trackr.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.trackr.domain.model.DataSourceType
import kotlinx.serialization.Serializable

@Serializable
sealed interface ScreenKey : NavKey {
    @Serializable
    data object Home : ScreenKey

    @Serializable
    data object Search : ScreenKey

    @Serializable
    data class LineDetail(
        val cityId: String = "034",
        val lineId: String = "",
        val targetOrder: Int = 0,
        val stopId: String = "",
        val source: DataSourceType = DataSourceType.CHELAILE,
        val lineName: String = ""
    ) : ScreenKey
}
