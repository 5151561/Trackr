package com.trackr.data.repository

import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.FavoriteGroup
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitGroup
import kotlinx.coroutines.flow.StateFlow

/**
 * 路线分组与多段换乘仓储接口
 */
interface IRouteGroupRepository {

    /**
     * 全部路线分组响应式数据流（按 sortOrder / 更新时间排序）
     */
    val groups: StateFlow<List<TransitGroup>>

    /**
     * 全部收藏分组响应式数据流（按 sortOrder 排序）
     */
    val favoriteGroups: StateFlow<List<FavoriteGroup>>

    /**
     * 新增路线分组
     */
    suspend fun addGroup(group: TransitGroup)

    /**
     * 更新已有路线分组
     */
    suspend fun updateGroup(group: TransitGroup)

    /**
     * 删除指定路线分组
     */
    suspend fun deleteGroup(groupId: String)

    /**
     * 将地图导入的临时方案转存为正式分组
     */
    suspend fun saveOrConvertTemporaryGroup(
        groupId: String,
        newName: String? = null,
        newCategory: GroupCategory? = null,
        customGroupId: String? = null
    )

    /**
     * 重新调整路线分组展示顺序
     */
    suspend fun reorderGroups(groupOrderIds: List<String>)

    /**
     * 获取指定 ID 的路线分组
     */
    fun getGroupById(groupId: String): TransitGroup?

    /**
     * 创建自定义收藏分组
     */
    suspend fun createFavoriteGroup(name: String): FavoriteGroup

    /**
     * 重命名自定义收藏分组
     */
    suspend fun renameFavoriteGroup(groupId: String, newName: String)

    /**
     * 删除自定义收藏分组（不删除组内路线，仅清除组内路线的 customGroupId）
     */
    suspend fun deleteFavoriteGroup(groupId: String)

    /**
     * 重新调整收藏分组展示顺序
     */
    suspend fun reorderFavoriteGroups(groupIds: List<String>)

    /**
     * 向指定行程段添加备选候选线路
     */
    suspend fun addCandidateLineToLeg(groupId: String, legIndex: Int, candidateLine: CandidateLine)
}
