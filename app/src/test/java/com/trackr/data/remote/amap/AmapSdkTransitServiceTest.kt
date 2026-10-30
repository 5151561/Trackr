package com.trackr.data.remote.amap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amap.api.services.busline.BusStationItem
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.BusPath
import com.amap.api.services.route.BusStep
import com.amap.api.services.route.RouteBusLineItem
import com.amap.api.services.route.RouteBusWalkItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AmapSdkTransitServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val service = AmapSdkTransitService(context)

    @Test
    fun convertBusPathToScheme_parsesCorrectly() {
        val busPath = BusPath().apply {
            duration = 1800L
            walkDistance = 120f
            cost = 2.0f
            steps = listOf(
                BusStep().apply {
                    walk = RouteBusWalkItem().apply {
                        distance = 120f
                    }
                    busLines = listOf(
                        RouteBusLineItem().apply {
                            busLineName = "302路(火车站--软件园)"
                            busLineId = "line_302"
                            passStationNum = 5
                            departureBusStation = BusStationItem().apply {
                                busStationName = "火车站"
                                latLonPoint = LatLonPoint(39.90, 116.40)
                            }
                            arrivalBusStation = BusStationItem().apply {
                                busStationName = "软件园"
                                latLonPoint = LatLonPoint(39.98, 116.45)
                            }
                        }
                    )
                }
            )
        }

        val scheme = AmapSdkTransitService.convertBusPathToScheme(
            path = busPath,
            originName = "火车站",
            destName = "软件园",
            cityName = "北京"
        )

        assertNotNull(scheme)
        assertEquals("302路 直达", scheme?.title)
        assertEquals(30, scheme?.durationMinutes)
        assertEquals(120, scheme?.walkingMeters)
        assertEquals(0, scheme?.transferCount)
        assertEquals(1, scheme?.group?.legs?.size)

        val leg = scheme?.group?.legs?.get(0)
        assertEquals("火车站", leg?.fromStopName)
        assertEquals("软件园", leg?.toStopName)
        assertEquals("302路", leg?.primaryLine?.lineName)
        assertEquals(120, leg?.walkingDistanceMeters)
    }

    @Test
    fun searchPoiTips_withoutKey_failsWithPrompt() = runBlocking {
        // 当传入空 Key 且没有 BuildConfig 时，应该返回失败并提示配置 Key
        val result = service.searchPoiTips("天安门", "北京", "")
        if (com.trackr.BuildConfig.AMAP_ANDROID_KEY.isBlank()) {
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("未配置有效的高德 Android Key") == true)
        }
    }
}
