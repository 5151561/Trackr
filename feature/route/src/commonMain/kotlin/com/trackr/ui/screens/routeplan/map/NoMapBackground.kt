package com.trackr.ui.screens.routeplan.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** 不画地图时只留底色（D2：不再有内置示意图），上面照常浮着搜索框和方案 */
@Composable
fun NoMapBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag(ROUTE_PLAN_NO_MAP_TAG)
    )
}
