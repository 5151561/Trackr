package com.trackr.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 基于 SharedPreferences 与 JSON 的路线分组持久化仓库实现
 */
class RouteGroupRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IRouteGroupRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _groups = MutableStateFlow<List<TransitGroup>>(loadInitialGroups())
    override val groups: StateFlow<List<TransitGroup>> = _groups.asStateFlow()

    private fun loadInitialGroups(): List<TransitGroup> {
        val rawJson = prefs.getString(KEY_GROUPS_JSON, null)
        if (rawJson.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            val loaded = json.decodeFromString<List<TransitGroup>>(rawJson)
            // 彻底清理历史测试注入的假数据（如 sample_commute_group 或含 sample_line_ 伪造标识的分组）
            val sanitized = loaded.filterNot { group ->
                group.id == "sample_commute_group" ||
                    group.legs.any { leg ->
                        leg.candidateLines.any { it.lineId.startsWith("sample_") }
                    }
            }
            if (sanitized.size != loaded.size) {
                persistGroupsInternal(sanitized)
            }
            sanitized.sortedBy { it.sortOrder }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun addGroup(group: TransitGroup) = withContext(ioDispatcher) {
        val current = _groups.value.toMutableList()
        val index = current.indexOfFirst { it.id == group.id }
        if (index >= 0) {
            current[index] = group.copy(updatedAt = System.currentTimeMillis())
        } else {
            val maxOrder = current.maxOfOrNull { it.sortOrder } ?: 0
            current.add(group.copy(sortOrder = maxOrder + 1, updatedAt = System.currentTimeMillis()))
        }
        persistGroups(current.sortedBy { it.sortOrder })
    }

    override suspend fun updateGroup(group: TransitGroup) = withContext(ioDispatcher) {
        val current = _groups.value.toMutableList()
        val index = current.indexOfFirst { it.id == group.id }
        if (index >= 0) {
            current[index] = group.copy(updatedAt = System.currentTimeMillis())
            persistGroups(current.sortedBy { it.sortOrder })
        }
    }

    override suspend fun deleteGroup(groupId: String) = withContext(ioDispatcher) {
        val current = _groups.value.filterNot { it.id == groupId }
        persistGroups(current)
    }

    override suspend fun saveOrConvertTemporaryGroup(
        groupId: String,
        newName: String?,
        newCategory: GroupCategory?
    ) = withContext(ioDispatcher) {
        val current = _groups.value.map { group ->
            if (group.id == groupId) {
                group.copy(
                    name = newName?.takeIf { it.isNotBlank() } ?: group.name,
                    category = newCategory ?: if (group.category == GroupCategory.TEMPORARY) GroupCategory.CUSTOM else group.category,
                    isTemporary = false,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                group
            }
        }
        persistGroups(current)
    }

    override suspend fun reorderGroups(groupOrderIds: List<String>) = withContext(ioDispatcher) {
        val groupMap = _groups.value.associateBy { it.id }
        val reordered = mutableListOf<TransitGroup>()
        groupOrderIds.forEachIndexed { index, id ->
            groupMap[id]?.let {
                reordered.add(it.copy(sortOrder = index))
            }
        }
        // 追加任何不在 groupOrderIds 中的遗留分组
        _groups.value.filterNot { it.id in groupOrderIds }.forEachIndexed { extraIndex, group ->
            reordered.add(group.copy(sortOrder = groupOrderIds.size + extraIndex))
        }
        persistGroups(reordered)
    }

    override fun getGroupById(groupId: String): TransitGroup? {
        return _groups.value.find { it.id == groupId }
    }

    private fun persistGroups(list: List<TransitGroup>) {
        _groups.value = list
        persistGroupsInternal(list)
    }

    private fun persistGroupsInternal(list: List<TransitGroup>) {
        try {
            val rawJson = json.encodeToString(list)
            prefs.edit().putString(KEY_GROUPS_JSON, rawJson).apply()
        } catch (_: Exception) {}
    }


    companion object {
        private const val PREFS_NAME = "trackr_route_groups"
        private const val KEY_GROUPS_JSON = "groups_list_json"
    }
}
