package com.trackr.data.provider

import com.trackr.data.provider.chelaile.ChelaileTransitProvider
import com.trackr.data.provider.model.LineDetailRequest
import com.trackr.data.provider.model.StopDetailRequest
import com.trackr.data.provider.mygolbs.MygolbsTransitProvider
import com.trackr.data.remote.chelaile.ChelaileApiClient
import com.trackr.data.remote.mygolbs.MygolbsApiClient
import com.trackr.domain.model.BusLineDirection
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.TransitCity
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransitProviderTest {

    private val testCity = TransitCity(
        cityId = "034",
        cityName = "上海",
        pinyin = "ShangHai",
        province = "直辖市"
    )

    private fun mockResponse(request: Request, bodyJson: String, code: Int = 200): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(bodyJson.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    private fun extractRequestBody(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    // =========================================================================
    // ChelaileTransitProvider Tests
    // =========================================================================

    @Test
    fun testChelaileProvider_supportsContracts() {
        val client = ChelaileApiClient(OkHttpClient())
        val provider = ChelaileTransitProvider(client)

        assertEquals(DataSourceType.CHELAILE, provider.sourceType)
        assertTrue(provider.supportsLocationRadar())

        assertTrue(provider.supportsLine("cl_line_1"))
        assertFalse(provider.supportsLine("mg:line_1"))

        assertTrue(provider.supportsStop(StopDetailRequest(stopId = "cl_s1")))
        assertFalse(provider.supportsStop(StopDetailRequest(stopId = "mg:s1")))
        assertFalse(provider.supportsStop(StopDetailRequest(physicalStId = "mg:phys1")))
        assertFalse(provider.supportsStop(StopDetailRequest(namesakeStId = "mg:name1")))
    }

    @Test
    fun testChelaileProvider_multiPhysicalStationConcurrentCompletion() = runBlocking {
        // 当首个响应中物理站台2只包含空壳占位线路（lineId 为空）时，Provider 并发补全该物理站台
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val physicalStId = request.url.queryParameter("physicalStId").orEmpty()

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

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val chelaileClient = ChelaileApiClient(okHttpClient)
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
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
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

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val chelaileClient = ChelaileApiClient(okHttpClient)
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
        val interceptor = Interceptor { chain ->
            val request = chain.request()
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

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val chelaileClient = ChelaileApiClient(okHttpClient)
        val provider = ChelaileTransitProvider(chelaileClient)

        val stops = provider.getNearbyStops(testCity, 31.20, 121.40, 4)

        assertEquals(1, stops.size)
        assertEquals("湖滨中路", stops[0].name)
    }

    @Test
    fun testChelaileProvider_getNearbyStopsConcurrentlyCompletesLackingRealtimeStops() = runBlocking {
        // 车来了接口只对前 2 个站点填充实时数据，第 3 个及之后的站点缺少实时数据，需并发补全
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

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

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val chelaileClient = ChelaileApiClient(okHttpClient)
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
    fun testChelaileProvider_getTimetable() = runBlocking {
        val interceptor = Interceptor { chain ->
            val request = chain.request()
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

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val chelaileClient = ChelaileApiClient(okHttpClient)
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
        val client = MygolbsApiClient(OkHttpClient())
        val provider = MygolbsTransitProvider(client)

        assertEquals(DataSourceType.MYGOLBS, provider.sourceType)
        assertFalse(provider.supportsLocationRadar())

        assertTrue(provider.supportsLine("mg:0:玉林:1路:0"))
        assertFalse(provider.supportsLine("cl_123"))

        assertTrue(provider.supportsStop(StopDetailRequest(stopId = "mg:450900:玉林:一中:0")))
        assertTrue(provider.supportsStop(StopDetailRequest(physicalStId = "mg:phys")))
        assertTrue(provider.supportsStop(StopDetailRequest(namesakeStId = "mg:name")))
        assertFalse(provider.supportsStop(StopDetailRequest(stopId = "cl_s1")))
    }

    @Test
    fun testMygolbsProvider_getStopDetailCleansSuffixAndParsesMg() = runBlocking {
        var capturedStationName = ""
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val body = extractRequestBody(request)

            // 解析请求体中的 STATIONNAME
            for (part in body.split("&")) {
                val kv = part.split("=")
                if (kv.size == 2 && kv[0] == "STATIONNAME") {
                    capturedStationName = java.net.URLDecoder.decode(kv[1], "UTF-8")
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

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val mygolbsClient = MygolbsApiClient(okHttpClient)
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
        val client = MygolbsApiClient(OkHttpClient())
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
    fun testMygolbsProvider_getSupportedCitiesConversion() = runBlocking {
        val interceptor = Interceptor { chain ->
            val request = chain.request()
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

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val mygolbsClient = MygolbsApiClient(okHttpClient)
        val provider = MygolbsTransitProvider(mygolbsClient)

        val cities = provider.getSupportedCities()
        val yulin = cities.find { it.cityName == "玉林" }
        assertNotNull(yulin)
        assertEquals("450900", yulin?.mygolbsCityCode)
        assertEquals("广西", yulin?.province)

        val beiliu = cities.find { it.cityName == "北流" }
        assertNotNull(beiliu)
        assertEquals("450981", beiliu?.mygolbsCityCode)
        assertEquals("广西", beiliu?.province)
    }

    @Test
    fun testMygolbsProvider_searchConcurrentLinesAndStops() = runBlocking {
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val body = extractRequestBody(request)

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

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val mygolbsClient = MygolbsApiClient(okHttpClient)
        val provider = MygolbsTransitProvider(mygolbsClient)

        val result = provider.search(testCity, "1")

        assertEquals(1, result.lines.size)
        assertEquals("1路", result.lines[0].name)
        assertEquals(1, result.stops.size)
        assertEquals("一中", result.stops[0].name)
    }
}
