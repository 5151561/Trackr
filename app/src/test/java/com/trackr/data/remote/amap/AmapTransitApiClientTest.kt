package com.trackr.data.remote.amap

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AmapTransitApiClientTest {

    private val client = AmapTransitApiClient(OkHttpClient())

    @Test
    fun parseTransitsToSchemes_parsesCorrectly() {
        val sampleJson = """
        [
          {
            "cost": "3.0",
            "duration": 2400,
            "walking_distance": 350,
            "segments": [
              {
                "walking": { "distance": 150 },
                "bus": {
                  "buslines": [
                    {
                      "name": "101路(红树林--海上世界)",
                      "id": "1101",
                      "departure_stop": { "name": "高新南十一道", "location": "113.951,22.531" },
                      "arrival_stop": { "name": "世界之窗地铁站", "location": "113.972,22.534" },
                      "via_num": 4
                    }
                  ]
                }
              },
              {
                "walking": { "distance": 200 },
                "bus": {
                  "buslines": [
                    {
                      "name": "52路(东昌总站--福田口岸)",
                      "id": "1152",
                      "departure_stop": { "name": "世界之窗地铁站", "location": "113.972,22.534" },
                      "arrival_stop": { "name": "科技园总站", "location": "113.985,22.540" },
                      "via_num": 3
                    }
                  ]
                }
              }
            ]
          }
        ]
        """.trimIndent()

        val jsonArray = JSONArray(sampleJson)
        val schemes = client.parseTransitsToSchemes(
            transitsArray = jsonArray,
            originName = "高新南",
            destName = "科技园",
            cityName = "深圳"
        )

        assertEquals(1, schemes.size)
        val scheme = schemes[0]
        assertEquals("101路 ➔ 52路 · 1次换乘", scheme.title)
        assertEquals(40, scheme.durationMinutes) // (2400 + 59) / 60 = 40
        assertEquals(350, scheme.walkingMeters)
        assertEquals("3.0", scheme.costYuan)
        assertEquals(1, scheme.transferCount)

        val group = scheme.group
        assertNotNull(group)
        assertTrue(group.isTemporary)
        assertEquals(2, group.legs.size)

        // 验证第 1 程
        val leg1 = group.legs[0]
        assertEquals("高新南十一道", leg1.fromStopName)
        assertEquals("世界之窗地铁站", leg1.toStopName)
        assertEquals("101路", leg1.primaryLine?.lineName)
        assertEquals(150, leg1.walkingDistanceMeters)

        // 验证第 2 程
        val leg2 = group.legs[1]
        assertEquals("世界之窗地铁站", leg2.fromStopName)
        assertEquals("科技园总站", leg2.toStopName)
        assertEquals("52路", leg2.primaryLine?.lineName)
        assertEquals(200, leg2.walkingDistanceMeters)
    }

    @Test
    fun parseTransitsToSchemes_parsesPolylineAndFinalWalk() {
        // 一程公交（带 polyline）+ 末段步行（segments 最后一段只有 walking，bus.buslines 空）
        val sampleJson = """
        [
          {
            "cost": "2.0",
            "duration": 1800,
            "walking_distance": 300,
            "segments": [
              {
                "walking": {
                  "steps": [
                    { "polyline": "121.100,31.100;121.101,31.101" }
                  ]
                },
                "bus": {
                  "buslines": [
                    {
                      "name": "100路",
                      "id": "b100",
                      "departure_stop": { "name": "起点站", "location": "121.102,31.102" },
                      "arrival_stop": { "name": "终点站", "location": "121.110,31.110" },
                      "via_num": 5,
                      "polyline": "121.102,31.102;121.105,31.105;121.110,31.110"
                    }
                  ]
                }
              },
              {
                "walking": {
                  "steps": [
                    { "polyline": "121.110,31.110;121.111,31.111;121.112,31.112" }
                  ]
                },
                "bus": { "buslines": [] }
              }
            ]
          }
        ]
        """.trimIndent()

        val schemes = client.parseTransitsToSchemes(
            transitsArray = JSONArray(sampleJson),
            originName = "家",
            destName = "公司",
            cityName = "上海"
        )

        assertEquals(1, schemes.size)
        val scheme = schemes[0]
        val leg = scheme.group.legs[0]

        // 乘车轨迹 ridePath 被解析（3 个点）
        assertEquals(3, leg.ridePath.size)
        assertEquals(31.102, leg.ridePath.first().lat, 1e-6)
        assertEquals(121.102, leg.ridePath.first().lng, 1e-6)

        // 上车前步行轨迹被解析（2 个点）
        assertEquals(2, leg.walkPathBefore.size)

        // 末段步行（末站 -> 目的地）被解析（3 个点）
        assertEquals(3, scheme.finalWalkPath.size)
        assertEquals(31.112, scheme.finalWalkPath.last().lat, 1e-6)
    }

    @Test
    fun parseTransitsToSchemes_multiCandidate_usesMainLineStation() {
        // 同一程多条候选线路：站名/坐标应以第一条（主线路）为准，不被最后一条覆盖
        val sampleJson = """
        [
          {
            "cost": "2.0",
            "duration": 1200,
            "walking_distance": 0,
            "segments": [
              {
                "bus": {
                  "buslines": [
                    {
                      "name": "1路",
                      "id": "l1",
                      "departure_stop": { "name": "主线上车站", "location": "120.0,30.0" },
                      "arrival_stop": { "name": "主线下车站", "location": "120.1,30.1" },
                      "via_num": 3
                    },
                    {
                      "name": "2路",
                      "id": "l2",
                      "departure_stop": { "name": "备选上车站", "location": "120.5,30.5" },
                      "arrival_stop": { "name": "备选下车站", "location": "120.6,30.6" },
                      "via_num": 4
                    }
                  ]
                }
              }
            ]
          }
        ]
        """.trimIndent()

        val schemes = client.parseTransitsToSchemes(
            transitsArray = JSONArray(sampleJson),
            originName = "起",
            destName = "终",
            cityName = "杭州"
        )

        val leg = schemes[0].group.legs[0]
        assertEquals("主线上车站", leg.fromStopName)
        assertEquals("主线下车站", leg.toStopName)
        assertEquals(30.0, leg.fromStopLat, 1e-6)
        assertEquals(2, leg.candidateLines.size)
    }

    @Test
    fun parseTransitsToSchemes_directBus_parsesCorrectly() {
        val sampleJson = """
        [
          {
            "cost": "2.0",
            "duration": 1800,
            "walking_distance": 100,
            "segments": [
              {
                "walking": { "distance": 100 },
                "bus": {
                  "buslines": [
                    {
                      "name": "911路",
                      "id": "911",
                      "departure_stop": { "name": "老西门", "location": "121.49,31.22" },
                      "arrival_stop": { "name": "静安寺", "location": "121.44,31.23" },
                      "via_num": 6
                    }
                  ]
                }
              }
            ]
          }
        ]
        """.trimIndent()

        val jsonArray = JSONArray(sampleJson)
        val schemes = client.parseTransitsToSchemes(
            transitsArray = jsonArray,
            originName = "老西门",
            destName = "静安寺",
            cityName = "上海"
        )

        assertEquals(1, schemes.size)
        val scheme = schemes[0]
        assertEquals("911路 直达", scheme.title)
        assertEquals(0, scheme.transferCount)
        assertEquals(1, scheme.group.legs.size)
        assertEquals("老西门", scheme.group.legs[0].fromStopName)
        assertEquals("静安寺", scheme.group.legs[0].toStopName)
    }
}
