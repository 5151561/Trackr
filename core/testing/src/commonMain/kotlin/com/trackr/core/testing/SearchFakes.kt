package com.trackr.core.testing

import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import kotlinx.coroutines.CompletableDeferred

/** [RecordingSearchRealtimeRepository] 收到的一次搜索 */
data class SearchCall(
    val city: TransitCity,
    val keyword: String,
    val userLat: Double?,
    val userLng: Double?
)

/**
 * 记录每次 `search` 调用的 [RealtimeRepository]；其余查询转给 [delegate]。
 *
 * - [searchResultFor] 按关键词给结果，默认 `TransitSearchResult(keyword)`。
 * - [pendingSearch] 不为 null 时，`search` 挂起到它完成（测「搜索中」状态、新词取消旧请求）。
 */
class RecordingSearchRealtimeRepository(
    private val delegate: FakeRealtimeRepository = FakeRealtimeRepository()
) : RealtimeRepository by delegate {

    val searchCalls: MutableList<SearchCall> = mutableListOf()

    var searchResultFor: (TransitCity, String) -> Result<TransitSearchResult> = { _, keyword ->
        Result.success(TransitSearchResult(keyword = keyword))
    }

    var pendingSearch: CompletableDeferred<Unit>? = null

    override suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double?,
        userLng: Double?
    ): Result<TransitSearchResult> {
        searchCalls += SearchCall(city, keyword, userLat, userLng)
        pendingSearch?.await()
        return searchResultFor(city, keyword)
    }
}
