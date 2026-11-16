package com.trackr.core.network.provider

import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.TransitCity
import com.trackr.core.network.provider.chelaile.ChelaileTransitProvider
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import com.trackr.core.network.provider.mygolbs.MygolbsTransitProvider
import com.trackr.core.network.remote.chelaile.ChelaileApiClient
import com.trackr.core.network.remote.mygolbs.MygolbsApiClient
import com.trackr.core.network.testing.MockHandler
import com.trackr.core.network.testing.mockHttpClient
import com.trackr.core.network.testing.mockResponse
import com.trackr.core.network.testing.unusedHttpClient
import io.ktor.http.decodeURLQueryComponent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TransitProviderTest {

    private val testCity = TransitCity(
        cityId = "034",
        cityName = "上海",
        pinyin = "ShangHai",
        province = "直辖市"
    )



    // =========================================================================
    // ChelaileTransitProvider Tests
    // =========================================================================

    @Test
    fun testChelaileProvider_supportsContracts() {
        val client = ChelaileApiClient(unusedHttpClient())
        val provider = ChelaileTransitProvider(client)

        assertEquals(DataSourceType.CHELAILE, provider.sourceType)
        assertTrue(provider.supportsLocationRadar())

        assertTrue(provider.supportsLine("cl_line_1"))
        assertFalse(provider.supportsLine("mg:line_1"))
        // 掌上出行的 id 不能被车来了接走（否则时刻表 / 站点会拿 lz: id 去请求车来了）
        assertFalse(provider.supportsLine("lz:1.2.3.4:8615:7:0:1路"))
        assertFalse(provider.supportsStop(StopDetailRequest(stopId = "lz:1.2.3.4:8615:3:县城")))

        assertTrue(provider.supportsStop(StopDetailRequest(stopId = "cl_s1")))
        assertFalse(provider.supportsStop(StopDetailRequest(stopId = "mg:s1")))
        assertFalse(provider.supportsStop(StopDetailRequest(physicalStId = "mg:phys1")))
        assertFalse(provider.supportsStop(StopDetailRequest(namesakeStId = "mg:name1")))
        assertTrue(provider.stopDetailIncludesBuses())
    }

    @Test
    fun testChelaileProvider_multiPhysicalStationConcurrentCompletion() = runBlocking {
        // 当首个响应中物理站台2只包含空壳占位线路（lineId 为空）时，Provider 并发补全该物理站台
        val interceptor = MockHandler { request ->
            val url = request.url
            val physicalStId = request.queryParameter("physicalStId").orEmpty()

            if (url.contains("encryptedPhyStnDetail.action")) {
                if (physicalStId == "phys_2") {
                    // 并发补全 phys_2 的独立请求
                    val jsonPhys2 = """
                    {
                      "jsonr": {
                        "data": {
                          "stationList": [
                            {
                              "sn": "人民广场(2号口)",
                              "stationId": "stn_2",
                              "physicalStId": "phys_2",
                              "lat": 31.232,
                              "lng": 121.472,
                              "lines": [
                                {
                                  "line": {
                                    "lineId": "cl_line_202",
                                    "name": "2路",
                                    "lineNo": "2",
                                    "direction": 0,
                                    "startSn": "A",
                                    "endSn": "B"
                                  },
                                  "stnStates": []
                                }
                              ]
                            }
                          ]
                        }
                      }
                    }
                    """.trimIndent()
                    mockResponse(request, jsonPhys2)
                } else {
                    // 主查询请求：站台1有完整线路，站台2只有占位空壳（lineId 为空）
                    val jsonMain = """
                    {
                      "jsonr": {
                        "data": {
                          "stationList": [
                            {
                              "sn": "人民广场(1号口)",
                              "stationId": "stn_1",
                              "physicalStId": "phys_1",
                              "lat": 31.231,
                              "lng": 121.471,
                              "lines": [
                                {
                                  "line": {
                                    "lineId": "cl_line_101",
                                    "name": "1路",
                                    "lineNo": "1",
                                    "direction": 0,
                                    "startSn": "X",
                                    "endSn": "Y"
                                  },
                                  "stnStates": []
                                }
                              ]
                            },
                            {
                              "sn": "人民广场(2号口)",
                              "stationId": "stn_2",
                              "physicalStId": "phys_2",
                              "lat": 31.232,
                              "lng": 121.472,
                              "lines": [
                                {
                                  "line": {
                                    "lineId": "",
                                    "name": "2路"
                                  },
                                  "stnStates": []
                                }
                              ]
                            }
                          ]
                        }
                      }
                    }
                    """.trimIndent()
                    mockResponse(request, jsonMain)
                }
            } else {
                mockResponse(request, """{"jsonr": {"data": {}}}""")
            }
        }

        val httpClient = mockHttpClient(handler = interceptor)
        val chelaileClient = ChelaileApiClient(httpClient)
        val provider = ChelaileTransitProvider(chelaileClient)

        val stops = provider.getStopDetail(testCity, StopDetailRequest(stopId = "stn_1", physicalStId = "phys_1"))

        assertEquals(2, stops.size)
        // 验证站台1信息完整
        assertEquals("phys_1", stops[0].physicalStId)
        assertEquals("cl_line_101", stops[0].lines[0].line.lineId)

        // 验证站台2被成功并发补全
        assertEquals("phys_2", stops[1].physicalStId)
        assertEquals("cl_line_202", stops[1].lines[0].line.lineId)
    }

    @Test
    fun testChelaileProvider_fallbackToNearbyWhenStopDetailEmpty() = runBlocking {
        // 主查询返回空站台，退化调用同源经纬度 getNearbyStops，过滤纯地铁站点并计算距离
        val interceptor = MockHandler { request ->
            val url = request.url
            if (url.contains("encryptedPhyStnDetail.action")) {
                mockResponse(request, """{"jsonr": {"data": {"stationList": []}}}""")
            } else if (url.contains("encryptedHomePage.action")) {
                val jsonNearby = """
                {
                  "jsonr": {
                    "data": {
                      "nearSts": [
                        {
                          "sn": "人民广场",
                          "sId": "stn_rmgc",
                          "lat": 31.2310,
                          "lng": 121.4710,
                          "isSubway": 0,
                          "distance": 0,
                          "lines": []
                        },
                        {
                          "sn": "人民广场(M)",
                          "sId": "stn_rmgc_subway",
                          "lat": 31.2312,
                          "lng": 121.4712,
                          "isSubway": 0,
                          "lines": []
                        }
                      ]
                    }
                  }
                }
                """.trimIndent()
                mockResponse(request, jsonNearby)
            } else {
                mockResponse(request, """{"jsonr": {"data": {}}}""")
            }
        }

        val httpClient = mockHttpClient(handler = interceptor)
        val chelaileClient = ChelaileApiClient(httpClient)
        val provider = ChelaileTransitProvider(chelaileClient)

        val request = StopDetailRequest(
            stopId = "unknown",
            stopName = "人民广场",
            stationLat = 31.2310,
            stationLng = 121.4710,
            userLat = 31.2400,
            userLng = 121.4800
        )

        val stops = provider.getStopDetail(testCity, request)

        // 验证纯地铁站点 人民广场(M) 被过滤，仅保留 人民广场
        assertEquals(1, stops.size)
        assertEquals("人民广场", stops[0].name)
        // 验证计算了离用户位置的距离
        assertNotNull(stops[0].distanceMeters)
        assertTrue(stops[0].distanceMeters!! > 0)
    }

    @Test
    fun testChelaileProvider_getNearbyStopsFiltersPureSubway() = runBlocking {
        val interceptor = MockHandler { request ->
            val jsonNearby = """
            {
              "jsonr": {
                "data": {
                  "nearSts": [
                    {
                      "sn": "湖滨中路",
                      "sId": "s_1",
                      "lat": 31.20,
                      "lng": 121.40,
                      "isSubway": 0,
                      "lines": []
                    },
                    {
                      "sn": "湖滨中路站（M）",
                      "sId": "s_2",
                      "lat": 31.21,
                      "lng": 121.41,
                      "isSubway": 0,
                      "lines": []
                    }
                  ]
                }
              }
            }
            """.trimIndent()
            mockResponse(request, jsonNearby)
        }

        val httpClient = mockHttpClient(handler = interceptor)
        val chelaileClient = ChelaileApiClient(httpClient)
        val provider = ChelaileTransitProvider(chelaileClient)

        val stops = provider.getNearbyStops(testCity, 31.20, 121.40, 4)

        assertEquals(1, stops.size)
        assertEquals("湖滨中路", stops[0].name)
    }

    @Test
    fun testChelaileProvider_getNearbyStopsConcurrentlyCompletesLackingRealtimeStops() = runBlocking {
        // 车来了接口只对前 2 个站点填充实时数据，第 3 个及之后的站点缺少实时数据，需并发补全
        val interceptor = MockHandler { request ->
            val url = request.url

            if (url.contains("encryptedHomePage.action")) {
                val jsonHomePage = """
                {
                  "jsonr": {
                    "data": {
                      "nearSts": [
                        {
                          "sn": "站点1",
                          "sId": "s_1",
                          "physicalStId": "p_1",
                          "lat": 31.20,
                          "lng": 121.40,
                          "isSubway": 0,
                          "lines": [
                            {
                              "line": {
                                "lineId": "l_1",
                                "name": "1路",
                                "shortDesc": "等待发车"
                              },
                              "stnStates": []
                            }
                          ]
                        },
                        {
                          "sn": "站点2",
                          "sId": "s_2",
                          "physicalStId": "p_2",
                          "lat": 31.21,
                          "lng": 121.41,
                          "isSubway": 0,
                          "lines": [
                            {
                              "line": {
                                "lineId": "l_2",
                                "name": "2路",
                                "shortDesc": "还有2站"
                              },
                              "stnStates": []
                            }
                          ]
                        },
                        {
                          "sn": "站点3",
                          "sId": "s_3",
                          "physicalStId": "p_3",
                          "lat": 31.22,
                          "lng": 121.42,
                          "isSubway": 0,
                          "lines": [
                            {
                              "line": {
                                "lineId": "l_3",
                                "name": "3路",
                                "shortDesc": ""
                              },
                              "stnStates": []
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """.trimIndent()
                mockResponse(request, jsonHomePage)
            } else if (url.contains("encryptedPhyStnDetail.action")) {
                // 补全第 3 个站点的实时到站信息
                val jsonDetailP3 = """
                {
                  "jsonr": {
                    "data": {
                      "stationList": [
                        {
                          "sn": "站点3",
                          "stationId": "s_3",
                          "physicalStId": "p_3",
                          "lat": 31.22,
                          "lng": 121.42,
                          "lines": [
                            {
                              "line": {
                                "lineId": "l_3",
                                "name": "3路",
                                "shortDesc": "还有1站约3分钟"
                              },
                              "stnStates": [
                                {
                                  "busId": "bus_301",
                                  "order": 1,
                                  "travelTime": 180
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """.trimIndent()
                mockResponse(request, jsonDetailP3)
            } else {
                mockResponse(request, """{"jsonr": {"data": {}}}""")
            }
        }

        val httpClient = mockHttpClient(handler = interceptor)
        val chelaileClient = ChelaileApiClient(httpClient)
        val provider = ChelaileTransitProvider(chelaileClient)

        val stops = provider.getNearbyStops(testCity, 31.20, 121.40, 4)

        assertEquals(3, stops.size)
        // 站点1保留原有状态
        assertEquals("等待发车", stops[0].lines[0].statusText)
        // 站点2保留原有状态
        assertEquals("还有2站", stops[1].lines[0].statusText)
        // 站点3原本缺少实时到站数据，成功被并发补全
        assertEquals("还有1站约3分钟", stops[2].lines[0].statusText)
        assertEquals(1, stops[2].lines[0].approachingBuses.size)
        assertEquals("bus_301", stops[2].lines[0].approachingBuses[0].busId)
    }

    @Test
    fun testChelaileProvider_stnStatesValueAndOrderMapping() = runBlocking {
        val interceptor = MockHandler { request ->
            val jsonHomePage = """
            {
              "jsonr": {
                "data": {
                  "nearSts": [
                    {
                      "sn": "延安东路西藏中路",
                      "sId": "s_14",
                      "physicalStId": "p_14",
                      "lat": 31.23,
                      "lng": 121.47,
                      "lines": [
                        {
                          "line": {
                            "lineId": "l_454",
                            "name": "454路",
                            "shortDesc": ""
                          },
                          "targetStation": {
                            "order": 14,
                            "sId": "s_14",
                            "sn": "延安东路西藏中路"
                          },
                          "stnStates": [
                            {
                              "busId": "ghost_bus",
                              "order": 0,
                              "travelTime": -1,
                              "value": -1
                            },
                            {
                              "busId": "bus_454_1",
                              "licence": "沪A-4541",
                              "order": 0,
                              "travelTime": 420,
                              "value": 1,
                              "state": 0,
                              "distanceToDest": 530
                            },
                            {
                              "busId": "bus_454_2",
                              "licence": "沪A-4542",
                              "order": 0,
                              "travelTime": 900,
                              "value": 5,
                              "state": 0,
                              "distanceToDest": 3200
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
              }
            }
            """.trimIndent()
            mockResponse(request, jsonHomePage)
        }

        val httpClient = mockHttpClient(handler = interceptor)
        val chelaileClient = ChelaileApiClient(httpClient)
        val provider = ChelaileTransitProvider(chelaileClient)

        val stops = provider.getNearbyStops(testCity, 31.23, 121.47, 1)
        assertEquals(1, stops.size)
        val lineStatus = stops[0].lines[0]
        assertEquals(14, lineStatus.targetOrder)

        // 验证空车对象已被过滤，且按紧迫度排序，第一辆为还有1站的 bus_454_1
        assertEquals(2, lineStatus.approachingBuses.size)
        val firstBus = lineStatus.approachingBuses[0]
        assertEquals("bus_454_1", firstBus.busId)
        // 目标站序14，距目标站还剩1站，车辆当前站序必须为 14 - 1 = 13
        assertEquals(13, firstBus.order)
        assertEquals(420, firstBus.etaSeconds)
        assertFalse(firstBus.isArrived)

        // 验证 UI 倒计时计算逻辑：剩余站数严格为 14 - 13 = 1 站（绝不是 14 站）
        val remainingStops = lineStatus.targetOrder - firstBus.order
        assertEquals(1, remainingStops)

        val secondBus = lineStatus.approachingBuses[1]
        assertEquals("bus_454_2", secondBus.busId)
        assertEquals(9, secondBus.order) // 14 - 5 = 9
    }

    @Test
    fun testChelaileProvider_getTimetable() = runBlocking {
        val interceptor = MockHandler { request ->
            val jsonSchedule = """
            {
              "jsonr": {
                "data": {
                  "timetable": {
                    "depDesc": "计划首班车 06:00",
                    "timetable": [
                      {
                        "fTime": "06:00",
                        "eTime": "08:00",
                        "times": ["06:00", "06:30", "07:00"]
                      }
                    ]
                  }
                }
              }
            }
            """.trimIndent()
            mockResponse(request, jsonSchedule)
        }

        val httpClient = mockHttpClient(handler = interceptor)
        val chelaileClient = ChelaileApiClient(httpClient)
        val provider = ChelaileTransitProvider(chelaileClient)

        val line = BusLineDirection(lineId = "cl_line_1", name = "1路", lineNo = "1", startStop = "A", endStop = "B")
        val timetable = provider.getTimetable(testCity, line)

        assertEquals("计划首班车 06:00", timetable.depDesc)
        assertTrue(timetable.todayDepartures.contains("06:00"))
    }

    // =========================================================================
    // MygolbsTransitProvider Tests
    // =========================================================================

    @Test
    fun testMygolbsProvider_supportsContracts() {
        val client = MygolbsApiClient(unusedHttpClient())
        val provider = MygolbsTransitProvider(client)

        assertEquals(DataSourceType.MYGOLBS, provider.sourceType)
        assertTrue(provider.supportsLocationRadar())

        assertTrue(provider.supportsLine("mg:0:玉林:1路:0"))
        assertFalse(provider.supportsLine("cl_123"))

        assertTrue(provider.supportsStop(StopDetailRequest(stopId = "mg:450900:玉林:一中:0")))
        assertTrue(provider.supportsStop(StopDetailRequest(physicalStId = "mg:phys")))
        assertTrue(provider.supportsStop(StopDetailRequest(namesakeStId = "mg:name")))
        assertFalse(provider.supportsStop(StopDetailRequest(stopId = "cl_s1")))
        assertFalse(provider.stopDetailIncludesBuses())
    }

    @Test
    fun testMygolbsProvider_getStopDetailCleansSuffixAndParsesMg() = runBlocking {
        var capturedStationName = ""
        val interceptor = MockHandler { request ->
            val body = request.body

            // 解析请求体中的 STATIONNAME
            for (part in body.split("&")) {
                val kv = part.split("=")
                if (kv.size == 2 && kv[0] == "STATIONNAME") {
                    capturedStationName = kv[1].decodeURLQueryComponent(plusIsSpace = true)
                }
            }

            val jsonStopLines = """
            {
              "status": "1",
              "data": [
                {
                  "lineName": "1路",
                  "upperOrDown": "1",
                  "from": "起点站",
                  "to": "终点站",
                  "comments": "票价2元",
                  "routeBean": {
                    "suTime": "06:30",
                    "euTime": "21:30"
                  }
                }
              ]
            }
            """.trimIndent()
            mockResponse(request, jsonStopLines)
        }

        val httpClient = mockHttpClient(handler = interceptor)
        val mygolbsClient = MygolbsApiClient(httpClient)
        val provider = MygolbsTransitProvider(mygolbsClient)

        // 场景 1：stopName 携带 " (掌上公交)" 后缀，必须清洗掉
        val req1 = StopDetailRequest(stopName = "市政府 (掌上公交)")
        val res1 = provider.getStopDetail(testCity, req1)

        assertEquals("市政府", capturedStationName)
        assertEquals(1, res1.size)
        assertEquals(1, res1[0].lines.size)
        assertEquals("1路", res1[0].lines[0].line.name)

        // 场景 2：stopId 为 mg: 协议格式且带 [掌上公交]
        val req2 = StopDetailRequest(stopId = "mg:450900:玉林:一中 [掌上公交]")
        val res2 = provider.getStopDetail(testCity, req2)
        assertEquals("一中", capturedStationName)
        assertEquals(1, res2.size)

        // 场景 3：空请求
        val res3 = provider.getStopDetail(testCity, StopDetailRequest())
        assertTrue(res3.isEmpty())
    }

    @Test
    fun testMygolbsProvider_getTimetableFallbackText() = runBlocking {
        val client = MygolbsApiClient(unusedHttpClient())
        val provider = MygolbsTransitProvider(client)

        val lineWithTimes = BusLineDirection(
            lineId = "mg:0:玉林:1路:0",
            name = "1路",
            startStop = "A",
            endStop = "B",
            firstTime = "06:00",
            lastTime = "22:00"
        )
        val timetable1 = provider.getTimetable(testCity, lineWithTimes)
        assertTrue(timetable1.depDesc.contains("06:00 - 22:00"))

        val lineNoTimes = BusLineDirection(
            lineId = "mg:0:玉林:1路:0",
            name = "1路",
            startStop = "A",
            endStop = "B",
            firstTime = "",
            lastTime = ""
        )
        val timetable2 = provider.getTimetable(testCity, lineNoTimes)
        assertEquals("该线路暂不提供首站预测时刻表，请参考运营首末班", timetable2.depDesc)
    }

    @Test
    fun testMygolbsProvider_stopDetailFillsCoordinatesAndDistance() = runBlocking {
        val interceptor = MockHandler { request ->
            mockResponse(
                request,
                """
                {
                  "status": 1,
                  "data": [
                    {"lineName": "1路", "upperOrDown": "1", "from": "A", "to": "B",
                     "routeBean": {"suTime": "06:30", "euTime": "21:30"}}
                  ]
                }
                """.trimIndent()
            )
        }

        val provider = MygolbsTransitProvider(MygolbsApiClient(mockHttpClient(handler = interceptor)))
        val yulin = TransitCity(cityId = "", cityName = "玉林市", mygolbsCityCode = "450900")

        val res = provider.getStopDetail(
            yulin,
            StopDetailRequest(
                stopName = "市政广场",
                stationLat = 22.653,
                stationLng = 110.183,
                userLat = 22.650,
                userLng = 110.180
            )
        )

        val stop = res.single()
        assertEquals(22.653, stop.wgsLat, 0.0001)
        assertEquals(110.183, stop.wgsLng, 0.0001)
        assertTrue((stop.distanceMeters ?: 0) in 1..2000, "应算出到站的几百米距离")
    }

    @Test
    fun testMygolbsProvider_searchStopsFillDistance() = runBlocking {
        val interceptor = MockHandler { request ->
            val body = request.body
            when {
                body.contains("CMD=114") -> mockResponse(request, """{"status":1,"buslines":[]}""")
                body.contains("CMD=110") -> mockResponse(
                    request,
                    """{"status":1,"busstations":[{"stationName":"市政广场","station_lat":"22.653","station_lon":"110.183"}]}"""
                )
                else -> error("unexpected request: ${body.take(30)}")
            }
        }

        val provider = MygolbsTransitProvider(MygolbsApiClient(mockHttpClient(handler = interceptor)))
        val yulin = TransitCity(cityId = "", cityName = "玉林市", mygolbsCityCode = "450900")

        val result = provider.search(yulin, "市政", userLat = 22.650, userLng = 110.180)

        val stop = result.stops.single()
        assertTrue((stop.distanceMeters ?: 0) in 1..2000, "搜索结果也要带距离")
    }

    @Test
    fun testMygolbsClient_timetableFromCmd207() = runBlocking {
        var captured207 = ""
        val interceptor = MockHandler { request ->
            val body = request.body
            when {
                body.contains("CMD=103") -> mockResponse(
                    request,
                    """{"status":1,"routeId":702,"firstLast":[{"first":"07:43","last":"19:41"}],"data":[]}"""
                )
                body.contains("CMD=207") -> {
                    captured207 = body
                    mockResponse(
                        request,
                        """
                        {
                          "status": 1,
                          "special": "0",
                          "nextTime": "16:06",
                          "list": [
                            {"group": "07:43~10:00", "peak": 1, "times": [
                              {"time": "07:43", "isDelete": 0},
                              {"time": "7:58", "isDelete": 0},
                              {"time": "08:27", "isDelete": 1}
                            ]},
                            {"group": "16:00~19:41", "peak": 0, "times": [
                              {"time": "16:06", "isDelete": 0}
                            ]}
                          ]
                        }
                        """.trimIndent()
                    )
                }
                else -> error("unexpected request: ${body.take(30)}")
            }
        }

        val client = MygolbsApiClient(mockHttpClient(handler = interceptor))
        val line = BusLineDirection(
            lineId = "mg:450900:玉林市:1路:1",
            name = "1路",
            startStop = "A",
            endStop = "B",
            source = DataSourceType.MYGOLBS
        )

        val timetable = client.getTimetable("450900", "玉林市", line, atTimeHHMM = "15:00")

        assertTrue(captured207.contains("ROUTEID=702"), "207 必须带 103 返回的 routeId")
        assertEquals(listOf("07:43", "07:58", "16:06"), timetable.todayDepartures)
        assertEquals("16:06", timetable.nextDeparture)
        assertEquals("历史推算班次", timetable.nextDepartureSource)
        assertEquals(2, timetable.segments.size)
        assertEquals("07:43", timetable.segments[0].fromTime)
        assertEquals("10:00", timetable.segments[0].toTime)
        assertEquals("早高峰", timetable.segments[0].intervalTips)
        assertTrue(timetable.depDesc.contains("历史数据"))
    }

    @Test
    fun testMygolbsClient_lineDetailReadsPlanTimeAndNextStop() = runBlocking {
        val interceptor = MockHandler { request ->
            val body = request.body
            when {
                body.contains("CMD=103") -> mockResponse(
                    request,
                    """
                    {
                      "status": 1,
                      "routeId": 17,
                      "firstLast": [{"first": "06:40", "last": "22:00"}],
                      "commonts": "票价2元",
                      "data": [
                        {"stationName": "A站", "stationOrder": 1, "station_lat": 22.0, "station_lng": 110.0, "stationsStatus": 1},
                        {"stationName": "B站", "stationOrder": 2, "station_lat": 22.001, "station_lng": 110.001, "stationsStatus": 1}
                      ]
                    }
                    """.trimIndent()
                )
                body.contains("CMD=104") -> mockResponse(
                    request,
                    """
                    {
                      "status": 1,
                      "runState": 0,
                      "hasReal": 1,
                      "planTime": "07:20",
                      "list": [
                        {"busNumber": "b1", "index": 1, "statusType": "2",
                         "bus_lat": 22.0005, "bus_lng": 110.0005, "stationName": "B站"}
                      ],
                      "routeOnStationRTimeInfoList": [
                        {"busNumber": "b1", "busToStationTime": 3.0, "busToStationDistance": 500}
                      ]
                    }
                    """.trimIndent()
                )
                else -> error("unexpected request: ${body.take(30)}")
            }
        }

        val client = MygolbsApiClient(mockHttpClient(handler = interceptor))
        val detail = client.getLineDetail("mg:450900:玉林市:1路:1", targetOrder = 2)

        assertEquals("07:20", detail.nextDepartureFromStart)
        val bus = detail.buses.single()
        assertEquals("B站", bus.nextStopName)
        assertEquals(180, bus.etaSeconds)
        assertEquals(500, bus.distanceToTargetMeters)
    }

    @Test
    fun testMygolbsProvider_getSupportedCitiesConversion() = runBlocking {
        val interceptor = MockHandler { request ->
            val jsonCities = """
            {
              "status": "1",
              "data": [
                {"cityname": "玉林", "cityCode": "450900"},
                {"cityname": "北流", "cityCode": "450981"}
              ]
            }
            """.trimIndent()
            mockResponse(request, jsonCities)
        }

        val httpClient = mockHttpClient(handler = interceptor)
        val mygolbsClient = MygolbsApiClient(httpClient)
        val provider = MygolbsTransitProvider(mygolbsClient)

        val cities = provider.getSupportedCities()
        val yulin = cities.find { it.cityName == "玉林" }
        assertNotNull(yulin)
        assertEquals("450900", yulin.mygolbsCityCode)
        assertEquals("广西", yulin.province)

        val beiliu = cities.find { it.cityName == "北流" }
        assertNotNull(beiliu)
        assertEquals("450981", beiliu.mygolbsCityCode)
        assertEquals("广西", beiliu.province)
    }

    @Test
    fun testMygolbsProvider_searchConcurrentLinesAndStops() = runBlocking {
        val interceptor = MockHandler { request ->
            val body = request.body

            if (body.contains("CMD=114") || body.contains("CMD=102")) {
                val jsonLines = """
                {
                  "status": "1",
                  "buslines": [
                    {
                      "lineName": "1路",
                      "upperOrDown": "1",
                      "from": "起点",
                      "to": "终点",
                      "comments": "2元"
                    }
                  ]
                }
                """.trimIndent()
                mockResponse(request, jsonLines)
            } else if (body.contains("CMD=110")) {
                val jsonStops = """
                {
                  "status": "1",
                  "busstations": [
                    {
                      "stationName": "一中"
                    }
                  ]
                }
                """.trimIndent()
                mockResponse(request, jsonStops)
            } else {
                mockResponse(request, """{"status": "1", "data": []}""")
            }
        }

        val httpClient = mockHttpClient(handler = interceptor)
        val mygolbsClient = MygolbsApiClient(httpClient)
        val provider = MygolbsTransitProvider(mygolbsClient)

        val result = provider.search(testCity, "1")

        assertEquals(1, result.lines.size)
        assertEquals("1路", result.lines[0].name)
        assertEquals(1, result.stops.size)
        assertEquals("一中", result.stops[0].name)
    }

    @Test
    fun testMygolbsProvider_getNearbyStopsAndReverseGeocode() = runBlocking {
        val interceptor = MockHandler { request ->
            val body = request.body

            when {
                body.contains("CMD=108") -> {
                    mockResponse(request, """{"status": "1", "msg": "上海市"}""")
                }
                body.contains("CMD=106") -> {
                    val jsonStops = """
                    {
                      "status": 1,
                      "data": [
                        {
                          "name": "人民广场（地铁站）",
                          "lat": "31.232",
                          "lon": "121.475",
                          "dis": 100
                        },
                        {
                          "name": "人民广场",
                          "lat": "31.231",
                          "lon": "121.476",
                          "dis": 200
                        }
                      ]
                    }
                    """.trimIndent()
                    mockResponse(request, jsonStops)
                }
                body.contains("CMD=105") -> {
                    val jsonLines = """
                    {
                      "status": "1",
                      "data": [
                        {
                          "lineName": "108路",
                          "upperOrDown": "1",
                          "from": "起点",
                          "to": "终点",
                          "neartext": "2站到达"
                        }
                      ]
                    }
                    """.trimIndent()
                    mockResponse(request, jsonLines)
                }
                else -> mockResponse(request, """{"status": "1", "data": []}""")
            }
        }

        val httpClient = mockHttpClient(handler = interceptor)
        val mygolbsClient = MygolbsApiClient(httpClient)
        val provider = MygolbsTransitProvider(mygolbsClient)

        // 1. 验证逆地理反查城市
        val city = provider.resolveCityByLocation(31.23, 121.47)
        assertEquals("上海市", city)

        // 2. 验证周边站点过滤地铁并补齐实时线路
        val stops = provider.getNearbyStops(testCity, 31.23, 121.47, 4)
        assertEquals(1, stops.size)
        assertEquals("人民广场", stops[0].name)
        assertEquals(200, stops[0].distanceMeters)
        assertEquals(DataSourceType.MYGOLBS, stops[0].source)
        assertEquals(1, stops[0].lines.size)
        assertEquals("108路", stops[0].lines[0].line.name)
        assertEquals("2站", stops[0].lines[0].statusText)
    }

    @Test
    fun testMygolbsProvider_getStopLines_mapsWaitingToNoRealtimeWhenHasRealIsZero() = runBlocking {
        val interceptor = MockHandler { request ->
            val body = request.body

            if (body.contains("CMD=105")) {
                val jsonLines = """
                {
                  "status": "1",
                  "data": [
                    {
                      "lineName": "312路",
                      "upperOrDown": "2",
                      "from": "呼兰路通河路",
                      "to": "人民广场",
                      "neartext": "等待发车",
                      "routeBean": {
                        "hasReal": 0
                      }
                    },
                    {
                      "lineName": "1路",
                      "upperOrDown": "1",
                      "from": "起点",
                      "to": "终点",
                      "neartext": "等待发车",
                      "routeBean": {
                        "hasReal": 1
                      }
                    }
                  ]
                }
                """.trimIndent()
                mockResponse(request, jsonLines)
            } else {
                mockResponse(request, """{"status": "1", "data": []}""")
            }
        }

        val httpClient = mockHttpClient(handler = interceptor)
        val mygolbsClient = MygolbsApiClient(httpClient)
        val provider = MygolbsTransitProvider(mygolbsClient)

        val request = StopDetailRequest(stopId = "mg:上海市:人民广场", stopName = "人民广场")
        val stops = provider.getStopDetail(testCity, request)

        assertEquals(1, stops.size)
        val lines = stops[0].lines
        assertEquals(2, lines.size)

        // hasReal == 0 应当被正确映射为 "未开通实时" 而不是盲目的 "等待发车"
        assertEquals("312路", lines[0].line.name)
        assertEquals("未开通实时", lines[0].statusText)

        // hasReal == 1 的真实发车保持原状 "等待发车"
        assertEquals("1路", lines[1].line.name)
        assertEquals("等待发车", lines[1].statusText)
    }

    @Test
    fun testMygolbsProvider_stopLines_keepsOnlyStopsCountFromNearText() = runBlocking {
        // 上游 neartext 形如「7站 [开往 市检察院]」「已到站 [到达 公交南站]」，方括号里是终点 / 下一站，卡片放不下
        val interceptor = MockHandler { request ->
            if (request.body.contains("CMD=105")) {
                val jsonLines = """
                {
                  "status": "1",
                  "data": [
                    { "lineName": "303路", "upperOrDown": "1", "neartext": "7站 [开往 市检察院]" },
                    { "lineName": "5路", "upperOrDown": "1", "neartext": "已到站 [到达 公交南站]" },
                    { "lineName": "2路", "upperOrDown": "1", "neartext": "2站到达" }
                  ]
                }
                """.trimIndent()
                mockResponse(request, jsonLines)
            } else {
                mockResponse(request, """{"status": "1", "data": []}""")
            }
        }

        val provider = MygolbsTransitProvider(MygolbsApiClient(mockHttpClient(handler = interceptor)))

        val request = StopDetailRequest(stopId = "mg:莆田市:公交北站", stopName = "公交北站")
        val lines = provider.getStopDetail(testCity, request)[0].lines

        assertEquals("7站", lines[0].statusText)
        assertEquals("已到站", lines[1].statusText)
        assertEquals("2站", lines[2].statusText)
    }
}
