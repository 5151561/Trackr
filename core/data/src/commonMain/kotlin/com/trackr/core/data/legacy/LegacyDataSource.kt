package com.trackr.core.data.legacy

import com.trackr.core.model.EngineMode

/**
 * 旧版本的数据源配置（`engine_mode`、聚合优先级 `aggregated_source_priority`）读出时的换算。
 *
 * Trackr 已不内置任何数据源，只剩用户自行接入一种：旧配置存过任何值都落成 [EngineMode.CUSTOM]，
 * 什么都没存过时返回 null（仓库套默认值）。
 *
 * 新存储导入（`LegacySettingsMigration`）和旧存储读取（USE_NEW_STORE 关时的 `SharedPrefsTransitStore`）都走这里，
 * 两边读出来的数据源一样。
 */
object LegacyDataSource {
    /** 返回 [EngineMode] 的名字；返回 null 表示没有旧配置 */
    fun resolve(engineMode: String?, aggregatedPriority: String?): String? =
        if (engineMode == null && aggregatedPriority == null) null else EngineMode.CUSTOM.name
}
