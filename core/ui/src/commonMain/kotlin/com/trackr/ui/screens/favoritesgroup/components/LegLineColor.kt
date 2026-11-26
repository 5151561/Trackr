package com.trackr.ui.screens.favoritesgroup.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.trackr.core.designsystem.theme.extendedColors

// 第 17 步从 LegTimelineItem.kt 挪来：收藏页（:feature:favorites）和规划页（:feature:route）都用

/** 第 1 程用 primary，第 2 程及以后用 legSecondary */
@Composable
fun legLineColor(legIndex: Int): Color =
    if (legIndex == 0) MaterialTheme.colorScheme.primary else MaterialTheme.extendedColors.legSecondary
