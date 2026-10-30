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
