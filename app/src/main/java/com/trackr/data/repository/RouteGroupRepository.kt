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
            val defaults = createDefaultSampleGroups()
            persistGroupsInternal(defaults)
            return defaults
        }
        return try {
            val loaded = json.decodeFromString<List<TransitGroup>>(rawJson)
            if (loaded.isEmpty()) {
                val defaults = createDefaultSampleGroups()
                persistGroupsInternal(defaults)
                defaults
            } else {
                loaded.sortedBy { it.sortOrder }
            }
        } catch (_: Exception) {
            val defaults = createDefaultSampleGroups()
            persistGroupsInternal(defaults)
            defaults
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

    private fun createDefaultSampleGroups(): List<TransitGroup> {
        val sampleGroup = TransitGroup(
            id = "sample_commute_group",
            name = "早高峰上班通勤",
            category = GroupCategory.COMMUTE,
            isTemporary = false,
            sortOrder = 0,
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "翠苑新村",
                    toStopName = "武林广场",
                    fromStopLat = 30.2850,
                    fromStopLng = 120.1250,
                    toStopLat = 30.2730,
                    toStopLng = 120.1600,
                    candidateLines = listOf(
                        CandidateLine(
                            cityId = "033",
                            cityName = "杭州",
                            lineId = "sample_line_156",
                            lineName = "156路",
                            source = DataSourceType.CHELAILE,
                            direction = 0,
                            fromStopOrder = 1,
                            toStopOrder = 7,
                            startStopName = "翠苑新村",
                            endStopName = "城站火车站"
                        )
                    )
                ),
                TransitLeg(
                    legIndex = 1,
                    fromStopName = "武林广场",
                    toStopName = "市民中心",
                    fromStopLat = 30.2730,
                    fromStopLng = 120.1600,
                    toStopLat = 30.2450,
                    toStopLng = 120.2100,
                    candidateLines = listOf(
                        CandidateLine(
                            cityId = "033",
                            cityName = "杭州",
                            lineId = "sample_line_m4",
                            lineName = "地铁4号线",
                            source = DataSourceType.CHELAILE,
                            direction = 0,
                            fromStopOrder = 8,
                            toStopOrder = 14,
                            startStopName = "彭埠",
                            endStopName = "浦沿"
                        )
                    ),
                    walkingDistanceMeters = 80
                )
            )
        )
        return listOf(sampleGroup)
    }

    companion object {
        private const val PREFS_NAME = "trackr_route_groups"
        private const val KEY_GROUPS_JSON = "groups_list_json"
    }
}
