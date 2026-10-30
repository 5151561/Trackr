package com.trackr.data.repository

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
        newCategory: GroupCategory? = null
    )

    /**
     * 重新调整分组展示顺序
     */
    suspend fun reorderGroups(groupOrderIds: List<String>)

    /**
     * 获取指定 ID 的路线分组
     */
    fun getGroupById(groupId: String): TransitGroup?
}
