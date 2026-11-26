package com.trackr.ui.screens.favoritesgroup

import com.trackr.core.model.CandidateLine

// 第 17 步从 FavoritesGroupScreen.kt 挪来：收藏页和规划页（:feature:route）都用

/** 跳线路详情时定位的站序：上车站；站序缺失时回落下车站 */
fun CandidateLine.boardStopOrderForDetail(): Int =
    if (fromStopOrder > 0) fromStopOrder else toStopOrder
