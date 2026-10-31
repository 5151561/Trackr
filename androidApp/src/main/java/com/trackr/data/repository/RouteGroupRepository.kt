package com.trackr.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.FavoriteGroup
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitGroup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 基于 SharedPreferences 与 JSON 的路线分组与自定义收藏持久化仓库实现
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

    /** 串行化路线分组与收藏分组的所有读改写（deleteFavoriteGroup 同时修改两者，共用一把锁） */
    private val mutex = Mutex()

    private val _groups = MutableStateFlow<List<TransitGroup>>(loadInitialGroups())
    override val groups: StateFlow<List<TransitGroup>> = _groups.asStateFlow()

    private val _favoriteGroups = MutableStateFlow<List<FavoriteGroup>>(loadInitialFavoriteGroups())
    override val favoriteGroups: StateFlow<List<FavoriteGroup>> = _favoriteGroups.asStateFlow()

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
        } catch (e: Exception) {
            // 解析失败时先备份原始数据，避免下次写入无声覆盖
            Log.e(TAG, "Failed to parse groups json, backing up raw data", e)
            backupCorruptJson(KEY_GROUPS_JSON, rawJson)
            emptyList()
        }
    }

    private fun loadInitialFavoriteGroups(): List<FavoriteGroup> {
        val rawJson = prefs.getString(KEY_FAVORITE_GROUPS_JSON, null)
        if (!rawJson.isNullOrBlank()) {
            try {
                val loaded = json.decodeFromString<List<FavoriteGroup>>(rawJson)
                if (loaded.isNotEmpty()) {
                    return loaded.sortedBy { it.sortOrder }
                }
            } catch (e: Exception) {
                // 解析失败时先备份原始数据，再写入默认分组
                Log.e(TAG, "Failed to parse favorite groups json, backing up raw data", e)
                backupCorruptJson(KEY_FAVORITE_GROUPS_JSON, rawJson)
            }
        }
        val defaultGroups = listOf(
            FavoriteGroup(id = "commute", name = "上班通勤", sortOrder = 0, isDefault = true),
            FavoriteGroup(id = "home", name = "回家线路", sortOrder = 1, isDefault = true)
        )
        persistFavoriteGroupsInternal(defaultGroups)
        return defaultGroups
    }

    override suspend fun addGroup(group: TransitGroup) = withContext(ioDispatcher) {
        mutex.withLock {
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
    }

    override suspend fun updateGroup(group: TransitGroup) = withContext(ioDispatcher) {
        mutex.withLock {
            val current = _groups.value.toMutableList()
            val index = current.indexOfFirst { it.id == group.id }
            if (index >= 0) {
                current[index] = group.copy(updatedAt = System.currentTimeMillis())
                persistGroups(current.sortedBy { it.sortOrder })
            }
        }
    }

    override suspend fun deleteGroup(groupId: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val current = _groups.value.filterNot { it.id == groupId }
            persistGroups(current)
        }
    }

    override suspend fun saveOrConvertTemporaryGroup(
        groupId: String,
        newName: String?,
        newCategory: GroupCategory?,
        customGroupId: String?
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            val current = _groups.value.map { group ->
                if (group.id == groupId) {
                    group.copy(
                        name = newName?.takeIf { it.isNotBlank() } ?: group.name,
                        category = newCategory ?: if (group.category == GroupCategory.TEMPORARY) GroupCategory.CUSTOM else group.category,
                        customGroupId = customGroupId ?: group.customGroupId,
                        isTemporary = false,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    group
                }
            }
            persistGroups(current)
        }
    }

    override suspend fun reorderGroups(groupOrderIds: List<String>) = withContext(ioDispatcher) {
        mutex.withLock {
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
    }

    override fun getGroupById(groupId: String): TransitGroup? {
        return _groups.value.find { it.id == groupId }
    }

    override suspend fun createFavoriteGroup(name: String): FavoriteGroup = withContext(ioDispatcher) {
        mutex.withLock {
            val current = _favoriteGroups.value.toMutableList()
            val maxOrder = current.maxOfOrNull { it.sortOrder } ?: -1
            val newGroup = FavoriteGroup(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                sortOrder = maxOrder + 1,
                isDefault = false
            )
            current.add(newGroup)
            persistFavoriteGroups(current.sortedBy { it.sortOrder })
            newGroup
        }
    }

    override suspend fun renameFavoriteGroup(groupId: String, newName: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val current = _favoriteGroups.value.toMutableList()
            val index = current.indexOfFirst { it.id == groupId }
            if (index >= 0) {
                current[index] = current[index].copy(name = newName.trim())
                persistFavoriteGroups(current.sortedBy { it.sortOrder })
            }
        }
    }

    override suspend fun deleteFavoriteGroup(groupId: String) = withContext(ioDispatcher) {
        mutex.withLock {
            // 1. 从收藏分组列表中移除
            val updatedFavoriteGroups = _favoriteGroups.value.filterNot { it.id == groupId }
            persistFavoriteGroups(updatedFavoriteGroups)

            // 2. 属于该分组的路线不删除，仅解除 customGroupId 关联归入未分组
            val currentGroups = _groups.value.toMutableList()
            var changed = false
            for (i in currentGroups.indices) {
                if (currentGroups[i].customGroupId == groupId) {
                    currentGroups[i] = currentGroups[i].copy(
                        customGroupId = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    changed = true
                }
            }
            if (changed) {
                persistGroups(currentGroups)
            }
        }
    }

    override suspend fun reorderFavoriteGroups(groupIds: List<String>) = withContext(ioDispatcher) {
        mutex.withLock {
            val groupMap = _favoriteGroups.value.associateBy { it.id }
            val reordered = mutableListOf<FavoriteGroup>()
            groupIds.forEachIndexed { index, id ->
                groupMap[id]?.let {
                    reordered.add(it.copy(sortOrder = index))
                }
            }
            _favoriteGroups.value.filterNot { it.id in groupIds }.forEachIndexed { extraIndex, group ->
                reordered.add(group.copy(sortOrder = groupIds.size + extraIndex))
            }
            persistFavoriteGroups(reordered)
        }
    }

    override suspend fun addCandidateLineToLeg(
        groupId: String,
        legIndex: Int,
        candidateLine: CandidateLine
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            val currentGroups = _groups.value.toMutableList()
            val groupIndex = currentGroups.indexOfFirst { it.id == groupId }
            if (groupIndex >= 0) {
                val group = currentGroups[groupIndex]
                val updatedLegs = group.legs.map { leg ->
                    if (leg.legIndex == legIndex) {
                        val alreadyHas = leg.candidateLines.any { it.lineId == candidateLine.lineId }
                        if (alreadyHas) {
                            leg
                        } else {
                            leg.copy(candidateLines = leg.candidateLines + candidateLine)
                        }
                    } else {
                        leg
                    }
                }
                currentGroups[groupIndex] = group.copy(
                    legs = updatedLegs,
                    updatedAt = System.currentTimeMillis()
                )
                persistGroups(currentGroups)
            }
        }
    }

    /**
     * 将无法解析的原始 JSON 备份到 `<key>_corrupt_backup_<时间戳>`，同一 key 只保留最近一份
     */
    private fun backupCorruptJson(key: String, rawJson: String) {
        val backupPrefix = key + CORRUPT_BACKUP_SUFFIX
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(backupPrefix) }
            .forEach { editor.remove(it) }
        editor.putString(backupPrefix + System.currentTimeMillis(), rawJson)
        editor.apply()
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

    private fun persistFavoriteGroups(list: List<FavoriteGroup>) {
        _favoriteGroups.value = list
        persistFavoriteGroupsInternal(list)
    }

    private fun persistFavoriteGroupsInternal(list: List<FavoriteGroup>) {
        try {
            val rawJson = json.encodeToString(list)
            prefs.edit().putString(KEY_FAVORITE_GROUPS_JSON, rawJson).apply()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "RouteGroupRepository"
        private const val PREFS_NAME = "trackr_route_groups"
        internal const val CORRUPT_BACKUP_SUFFIX = "_corrupt_backup_"
        private const val KEY_GROUPS_JSON = "groups_list_json"
        private const val KEY_FAVORITE_GROUPS_JSON = "favorite_groups_list_json"
    }
}
