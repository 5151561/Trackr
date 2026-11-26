package com.trackr.core.data.route

import com.trackr.core.data.local.LocalMigrationFlags
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.TransitGroup
import com.trackr.data.repository.IRouteGroupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * 第 17 步从 `FavoritesGroupViewModel` 拆出来、几个新 ViewModel 共用的路线保存逻辑（原样搬，不改行为）。
 */

/** 路线是否属于收藏分组 [favGroupId]（commute / home 兼容旧数据的归属规则） */
fun isGroupInFavoriteGroup(group: TransitGroup, favGroupId: String): Boolean {
    return when (favGroupId) {
        "commute" -> group.customGroupId == "commute" || (group.customGroupId == null && group.category == GroupCategory.COMMUTE)
        "home" -> group.customGroupId == "home" || (group.customGroupId == null && group.name.contains("返程"))
        else -> group.customGroupId == favGroupId
    }
}

/**
 * 收藏页当前选中的分组 chip。原来是 `FavoritesGroupViewModel` 的一个字段，规划页「存入收藏」选「全部」时
 * 也回落到它；拆成两个 ViewModel 后放这里共用（Koin `single`），行为不变。
 */
class FavoriteGroupSelection {
    private val _selectedFavoriteGroupId = MutableStateFlow<String?>(null)
    val selectedFavoriteGroupId: StateFlow<String?> = _selectedFavoriteGroupId.asStateFlow()

    fun select(groupId: String?) {
        _selectedFavoriteGroupId.value = groupId
    }
}

/**
 * 存路线：回落到选中的分组 → 对齐实时源 → 写仓库 → 存进「上班通勤」时自动补一条返程到「回家」。
 * 原来 `addPlannedSchemeAsFavorite` 和 `saveGroup` 各有一份，原样搬到这里（两份的差别保留）。
 */
class RouteGroupSaver(
    private val groupRepository: IRouteGroupRepository,
    private val alignment: RouteAlignmentService,
    private val selection: FavoriteGroupSelection
) {

    /** 规划方案 / 临时追踪的路线转存为正式收藏（原 `addPlannedSchemeAsFavorite(group, …)`） */
    suspend fun saveAsFavorite(
        group: TransitGroup,
        customGroupId: String?,
        category: GroupCategory,
        customName: String?
    ) {
        val targetGroupId = customGroupId
            ?: selection.selectedFavoriteGroupId.value
            ?: if (group.category == GroupCategory.COMMUTE) "commute" else null
        val targetCategory = if (targetGroupId == "commute") GroupCategory.COMMUTE else category
        val preparedGroup = group.copy(
            isTemporary = false,
            customGroupId = targetGroupId,
            category = targetCategory,
            name = customName?.takeIf { it.isNotBlank() } ?: group.name
        )
        val alignedGroup = alignment.alignTransitGroupWithRealtimeSource(preparedGroup)
        groupRepository.addGroup(alignedGroup)
        addReverseIfCommute(targetGroupId, alignedGroup)
    }

    /** 创建或更新自定义分组（原 `saveGroup`） */
    suspend fun saveGroup(group: TransitGroup) {
        val targetGroupId = group.customGroupId
            ?: selection.selectedFavoriteGroupId.value
            ?: if (group.category == GroupCategory.COMMUTE) "commute" else null
        val targetCategory = if (targetGroupId == "commute") GroupCategory.COMMUTE else group.category
        val preparedGroup = group.copy(
            customGroupId = targetGroupId,
            category = targetCategory
        )
        val alignedGroup = alignment.alignTransitGroupWithRealtimeSource(preparedGroup)
        groupRepository.addGroup(alignedGroup)
        addReverseIfCommute(targetGroupId, alignedGroup)
    }

    private suspend fun addReverseIfCommute(targetGroupId: String?, alignedGroup: TransitGroup) {
        if (targetGroupId == "commute") {
            val newOrigin = alignedGroup.destinationStopName
            val newDest = alignedGroup.originStopName
            val existingHome = groupRepository.groups.value.filter { isGroupInFavoriteGroup(it, "home") }
            val hasReverse = existingHome.any { it.originStopName == newOrigin && it.destinationStopName == newDest }
            if (!hasReverse && newOrigin.isNotBlank() && newDest.isNotBlank()) {
                val reverseGroup = alignment.buildReverseTransitGroup(alignedGroup, targetGroupId = "home")
                groupRepository.addGroup(reverseGroup)
            }
        }
    }
}

/**
 * 收藏分组的一次性数据修复（原 `FavoritesGroupViewModel.runDataMigrationIfNeeded`），版本号存在设置里，只跑一次。
 * 仍由收藏页的 ViewModel 创建时触发，时机和原来一样。
 */
class FavoritesGroupDataFix(
    private val groupRepository: IRouteGroupRepository,
    private val alignment: RouteAlignmentService,
    /** 为 null 时（测试）每次都跑，不做版本短路 */
    private val migrationFlags: LocalMigrationFlags? = null
) {
    companion object {
        const val CURRENT_MIGRATION_VERSION = 2
    }

    suspend fun runIfNeeded() {
        // migrationFlags 为空（例如测试环境）时退化为每次都执行，不做版本短路
        val migratedVersion = migrationFlags?.favoritesGroupMigrationVersion() ?: 0
        if (migratedVersion >= CURRENT_MIGRATION_VERSION) return

        // 1. 补齐遗留通勤分组的 customGroupId（纯本地判定，无网络请求）
        val currentGroups = groupRepository.groups.value
        for (g in currentGroups) {
            if (g.customGroupId == null && g.category == GroupCategory.COMMUTE) {
                groupRepository.updateGroup(g.copy(customGroupId = "commute"))
            }
        }

        // 2. 为通勤路线补全缺失的返程（回家）路线
        val latestGroups = groupRepository.groups.value
        val homeRoutes = latestGroups.filter { isGroupInFavoriteGroup(it, "home") }
        val commuteRoutes = latestGroups.filter { isGroupInFavoriteGroup(it, "commute") }
        for (commute in commuteRoutes) {
            val newOrigin = commute.destinationStopName
            val newDest = commute.originStopName
            val hasReverse = homeRoutes.any {
                it.originStopName == newOrigin && it.destinationStopName == newDest
            }
            if (!hasReverse && newOrigin.isNotBlank() && newDest.isNotBlank()) {
                val reversed = alignment.buildReverseTransitGroup(commute, targetGroupId = "home")
                groupRepository.addGroup(reversed)
            }
        }

        migrationFlags?.setFavoritesGroupMigrationVersion(CURRENT_MIGRATION_VERSION)
    }
}
