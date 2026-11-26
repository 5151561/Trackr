package com.trackr.core.network.provider.custom

import com.trackr.core.model.CustomSourceConfig
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.TransitCity
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.testing.MockReply
import com.trackr.core.network.testing.MockRequest
import com.trackr.core.network.testing.mockHttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomTransitProviderTest {

    private val city = TransitCity(cityId = "sh", cityName = "上海", mygolbsCityCode = "310100")

    private fun provider(
        config: CustomSourceConfig = CustomSourceConfig(baseUrl = "https://example.test/trackr/", token = "secret"),
        requests: MutableList<MockRequest> = mutableListOf(),
        route: (MockRequest) -> MockReply
    ) = CustomTransitProvider(
        mockHttpClient { request -> requests += request; route(request) }
    ) { config }

    @Test
    fun notConfigured_citiesEmpty_queriesFailWithHint() = runTest {
        val requests = mutableListOf<MockRequest>()
        val p = provider(CustomSourceConfig(), requests) { MockReply("[]") }

        assertTrue(p.getSupportedCities().isEmpty())
        assertNull(p.listAllLines(city))
        assertFailsWith<DataSourceNotConfiguredException> { p.search(city, "49") }
        assertTrue(requests.isEmpty(), "未接入时不应发出任何请求")
    }

    @Test
    fun httpUrl_isNotConfigured() {
        assertFalse(CustomSourceConfig(baseUrl = "http://example.test").isConfigured)
        assertFalse(CustomSourceConfig(baseUrl = "https://").isConfigured)
        assertTrue(CustomSourceConfig(baseUrl = " https://example.test/ ").isConfigured)
    }

    @Test
    fun search_postsJsonWithBearerToken_andParsesResult() = runTest {
        val requests = mutableListOf<MockRequest>()
        val p = provider(requests = requests) { request ->
            when (request.encodedPath) {
                "/trackr/v1/manifest" -> MockReply("", 404)
                "/trackr/v1/search" -> MockReply(
                    """{"keyword":"49","lines":[{"name":"49路","lineNo":"49","source":"LEGACY_SOURCE","extra":1,
                       "directions":[{"lineId":"sh-49-0","name":"49路","startStop":"A","endStop":"B"}]}]}"""
                )
                else -> MockReply("", 500)
            }
        }

        val result = p.search(city, "49", userLat = 31.2, userLng = 121.4)

        assertEquals("49路", result.lines.single().name)
        assertEquals(DataSourceType.CUSTOM, result.lines.single().source, "未知数据源名一律当自定义数据源")
        assertEquals("sh-49-0", result.lines.single().directions.single().lineId)
        val search = requests.single { it.encodedPath == "/trackr/v1/search" }
        assertEquals("POST", search.data.method.value)
        assertEquals("Bearer secret", search.header("Authorization"))
        assertTrue(search.contentType.orEmpty().startsWith("application/json"))
        assertTrue(search.body.contains("\"keyword\":\"49\""))
        assertTrue(search.body.contains("\"cityName\":\"上海\""))
        assertTrue(search.body.contains("\"adcode\":\"310100\""))
    }

    @Test
    fun lineDetail_parsesMinimalPayloadWithDefaults() = runTest {
        val p = provider { request ->
            when (request.encodedPath) {
                "/trackr/v1/line" -> MockReply(
                    """{"line":{"lineId":"sh-49-0","name":"49路","startStop":"A","endStop":"B"},
                       "stops":[{"stopId":"s1","name":"A","order":1},{"stopId":"s2","name":"B","order":2}],
                       "buses":[{"busId":"b1","plateOrFleetNo":"沪A12345","order":2,"etaSeconds":120}],
                       "operatingState":"WAITING"}"""
                )
                else -> MockReply("", 404)
            }
        }

        val detail = p.getLineDetail(city, LineDetailRequest(lineId = "sh-49-0", targetOrder = 2))

        assertEquals(2, detail.stops.size)
        assertEquals(120, detail.buses.single().etaSeconds)
        assertEquals(LineOperatingState.WAITING, detail.operatingState)
        assertNull(detail.reverseDirection)
    }

    @Test
    fun manifestCapabilities_disableOptionalEndpoints() = runTest {
        val requests = mutableListOf<MockRequest>()
        val p = provider(requests = requests) { request ->
            when (request.encodedPath) {
                "/trackr/v1/manifest" -> MockReply(
                    """{"protocolVersion":1,"capabilities":{"nearby":false,"timetable":false,"stopDetailIncludesBuses":false}}"""
                )
                "/trackr/v1/cities" -> MockReply("""[{"cityId":"sh","cityName":"上海"}]""")
                else -> MockReply("", 500)
            }
        }

        assertEquals("上海", p.getSupportedCities().single().cityName)
        assertTrue(p.getNearbyStops(city, 31.2, 121.4).isEmpty())
        assertFalse(p.supportsLocationRadar())
        assertFalse(p.stopDetailIncludesBuses())
        assertNull(p.resolveCityByLocation(31.2, 121.4))
        assertTrue(requests.none { it.encodedPath.endsWith("/nearby") || it.encodedPath.endsWith("/geocode") })
    }

    @Test
    fun httpError_surfacesAsIOException_withoutQuery() = runTest {
        val p = provider { MockReply("boom", 503) }
        val error = assertFailsWith<kotlinx.io.IOException> { p.search(city, "49") }
        assertTrue(error.message.orEmpty().contains("503"))
        assertFalse(error.message.orEmpty().contains("secret"))
    }
}
