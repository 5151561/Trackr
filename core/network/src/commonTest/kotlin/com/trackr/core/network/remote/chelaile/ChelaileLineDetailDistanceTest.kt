package com.trackr.core.network.remote.chelaile

import com.trackr.core.network.testing.MockReply
import com.trackr.core.network.testing.mockHttpClient
import com.trackr.core.network.testing.mockResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 车来了线路详情的「距本站距离」回归测试（issue #60）。
 *
 * `encryptedLineDetail.action` 不带 `targetOrder`，上游默认按终点站或定位最近站算 `distanceToWaitStn`；
 * 只有带 `targetOrder` 的 `encryptedBusDetail.action` 返回的 `distanceToWaitStn` 才对应用户选的候车站。
 */
class ChelaileLineDetailDistanceTest {

    private fun lineDetailBody(
        serverTargetOrder: Int,
        bus1Distance: Int,
        bus2Distance: Int
    ): String = """
        {
          "jsonr": {
            "data": {
              "targetOrder": $serverTargetOrder,
              "line": {
                "lineId": "b6_line",
                "name": "B6",
                "lineNo": "B6",
                "direction": 0,
                "startSn": "七都南公交枢纽",
                "endSn": "火车南站公交枢纽"
              },
              "stations": [
                {"sId": "s29", "sn": "西站", "order": 29, "wgsLat": 28.01, "wgsLng": 120.65},
                {"sId": "s30", "sn": "奥林匹克广场", "order": 30, "wgsLat": 28.02, "wgsLng": 120.66},
                {"sId": "s31", "sn": "半腰桥", "order": 31, "wgsLat": 28.03, "wgsLng": 120.67}
              ],
              "buses": [
                {
                  "busId": "bus_approaching",
                  "licence": "浙C58632",
                  "order": 30,
                  "lat": 28.019,
                  "lng": 120.659,
                  "distanceToWaitStn": $bus1Distance
                },
                {
                  "busId": "bus_passed",
                  "licence": "浙C8163D",
                  "order": 31,
                  "lat": 28.031,
                  "lng": 120.671,
                  "distanceToWaitStn": $bus2Distance
                }
              ]
            }
          }
        }
    """.trimIndent()

    private fun busDetailBody(
        bus1Distance: Int,
        bus2Distance: Int,
        includeTravels: Boolean = true
    ): String {
        val travels1 = if (includeTravels) {
            """[{"travelTime": 30, "recommTip": "17:41"}]"""
        } else {
            "[]"
        }
        return """
            {
              "jsonr": {
                "data": {
                  "targetOrder": 30,
                  "buses": [
                    {
                      "busId": "bus_approaching",
                      "distanceToWaitStn": $bus1Distance,
                      "travels": $travels1
                    },
                    {
                      "busId": "bus_passed",
                      "distanceToWaitStn": $bus2Distance,
                      "travels": []
                    }
                  ]
                }
              }
            }
        """.trimIndent()
    }

    @Test
    fun getLineDetail_usesDistanceFromBusDetailForTargetOrder() = runBlocking {
        val client = ChelaileApiClient(
            mockHttpClient { request ->
                when {
                    request.encodedPath.endsWith("line!encryptedLineDetail.action") ->
                        mockResponse(request, lineDetailBody(serverTargetOrder = 45, bus1Distance = 12500, bus2Distance = 11200))
                    request.encodedPath.endsWith("line!encryptedBusDetail.action") ->
                        mockResponse(request, busDetailBody(bus1Distance = 190, bus2Distance = -1))
                    else -> error("unexpected path: ${request.encodedPath}")
                }
            }
        )

        val detail = client.getLineDetail(cityId = "0577", lineId = "b6_line", targetOrder = 30)

        val approaching = detail.buses.first { it.busId == "bus_approaching" }
        val passed = detail.buses.first { it.busId == "bus_passed" }
        assertEquals(190, approaching.distanceToTargetMeters)
        assertEquals(30, approaching.etaSeconds)
        assertNull(passed.distanceToTargetMeters)
    }

    @Test
    fun getLineDetail_whenBusDetailFailsAndLineDetailTargetDiffers_discardsWrongDistance() = runBlocking {
        val client = ChelaileApiClient(
            mockHttpClient { request ->
                when {
                    request.encodedPath.endsWith("line!encryptedLineDetail.action") ->
                        mockResponse(request, lineDetailBody(serverTargetOrder = 45, bus1Distance = 12500, bus2Distance = 11200))
                    request.encodedPath.endsWith("line!encryptedBusDetail.action") ->
                        MockReply("server error", code = 500)
                    else -> error("unexpected path: ${request.encodedPath}")
                }
            }
        )

        val detail = client.getLineDetail(cityId = "0577", lineId = "b6_line", targetOrder = 30)

        assertNull(detail.buses.first { it.busId == "bus_approaching" }.distanceToTargetMeters)
        assertNull(detail.buses.first { it.busId == "bus_passed" }.distanceToTargetMeters)
    }

    @Test
    fun getLineDetail_whenBusDetailFailsAndLineDetailTargetMatches_fallsBackToLineDetailDistance() = runBlocking {
        val client = ChelaileApiClient(
            mockHttpClient { request ->
                when {
                    request.encodedPath.endsWith("line!encryptedLineDetail.action") ->
                        mockResponse(request, lineDetailBody(serverTargetOrder = 30, bus1Distance = 190, bus2Distance = -1))
                    request.encodedPath.endsWith("line!encryptedBusDetail.action") ->
                        MockReply("server error", code = 500)
                    else -> error("unexpected path: ${request.encodedPath}")
                }
            }
        )

        val detail = client.getLineDetail(cityId = "0577", lineId = "b6_line", targetOrder = 30)

        assertEquals(190, detail.buses.first { it.busId == "bus_approaching" }.distanceToTargetMeters)
        assertNull(detail.buses.first { it.busId == "bus_passed" }.distanceToTargetMeters)
    }

    @Test
    fun getLineDetail_withoutTargetOrder_neverExposesDistance() = runBlocking {
        val client = ChelaileApiClient(
            mockHttpClient { request ->
                mockResponse(request, lineDetailBody(serverTargetOrder = 45, bus1Distance = 12500, bus2Distance = 11200))
            }
        )

        val detail = client.getLineDetail(cityId = "0577", lineId = "b6_line", targetOrder = null)

        assertNull(detail.buses.first { it.busId == "bus_approaching" }.distanceToTargetMeters)
        assertNull(detail.buses.first { it.busId == "bus_passed" }.distanceToTargetMeters)
    }

    @Test
    fun getLineDetail_busDetailWithoutTravels_stillParsesDistance() = runBlocking {
        val client = ChelaileApiClient(
            mockHttpClient { request ->
                when {
                    request.encodedPath.endsWith("line!encryptedLineDetail.action") ->
                        mockResponse(request, lineDetailBody(serverTargetOrder = 45, bus1Distance = 12500, bus2Distance = 11200))
                    request.encodedPath.endsWith("line!encryptedBusDetail.action") ->
                        mockResponse(request, busDetailBody(bus1Distance = 4800, bus2Distance = -1, includeTravels = false))
                    else -> error("unexpected path: ${request.encodedPath}")
                }
            }
        )

        val detail = client.getLineDetail(cityId = "0577", lineId = "b6_line", targetOrder = 30)

        assertEquals(4800, detail.buses.first { it.busId == "bus_approaching" }.distanceToTargetMeters)
    }
}
