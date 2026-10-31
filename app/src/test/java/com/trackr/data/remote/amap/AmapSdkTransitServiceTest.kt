package com.trackr.data.remote.amap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amap.api.services.busline.BusStationItem
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.route.BusPath
import com.amap.api.services.route.BusStep
import com.amap.api.services.route.RouteBusLineItem
import com.amap.api.services.route.RouteBusWalkItem
import com.amap.api.services.route.WalkStep
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
    fun convertBusPathToScheme_parsesPolylineAndFinalWalk() {
        val busPath = BusPath().apply {
            duration = 1800L
            walkDistance = 200f
            cost = 2.0f
            steps = listOf(
                // 乘车前步行 + 公交（带 polyline）
                BusStep().apply {
                    walk = RouteBusWalkItem().apply {
                        distance = 100f
                        steps = listOf(
                            WalkStep().apply {
                                polyline = listOf(LatLonPoint(31.10, 121.10), LatLonPoint(31.11, 121.11))
                            }
                        )
                    }
                    busLines = listOf(
                        RouteBusLineItem().apply {
                            busLineName = "100路"
                            busLineId = "b100"
                            passStationNum = 5
                            departureBusStation = BusStationItem().apply {
                                busStationName = "起点站"
                                latLonPoint = LatLonPoint(31.12, 121.12)
                            }
                            arrivalBusStation = BusStationItem().apply {
                                busStationName = "终点站"
                                latLonPoint = LatLonPoint(31.20, 121.20)
                            }
                            polyline = listOf(
                                LatLonPoint(31.12, 121.12),
                                LatLonPoint(31.16, 121.16),
                                LatLonPoint(31.20, 121.20)
                            )
                        }
                    )
                },
                // 末段步行（只有 walk，无 busLines）
                BusStep().apply {
                    walk = RouteBusWalkItem().apply {
                        distance = 100f
                        steps = listOf(
                            WalkStep().apply {
                                polyline = listOf(
                                    LatLonPoint(31.20, 121.20),
                                    LatLonPoint(31.21, 121.21),
                                    LatLonPoint(31.22, 121.22)
                                )
                            }
                        )
                    }
                    busLines = emptyList()
                }
            )
        }

        val scheme = AmapSdkTransitService.convertBusPathToScheme(
            path = busPath,
            originName = "家",
            destName = "公司",
            cityName = "上海"
        )

        assertNotNull(scheme)
        val leg = scheme!!.group.legs[0]
        assertEquals(3, leg.ridePath.size)
        assertEquals(2, leg.walkPathBefore.size)
        assertEquals(31.12, leg.ridePath.first().lat, 1e-6)
        // 末段步行解析
        assertEquals(3, scheme.finalWalkPath.size)
        assertEquals(31.22, scheme.finalWalkPath.last().lat, 1e-6)
    }

    @Test
    fun convertBusPathToScheme_multiCandidate_usesMainLineStation() {
        val busPath = BusPath().apply {
            duration = 1200L
            walkDistance = 0f
            cost = 2.0f
            steps = listOf(
                BusStep().apply {
                    busLines = listOf(
                        RouteBusLineItem().apply {
                            busLineName = "1路"
                            busLineId = "l1"
                            passStationNum = 3
                            departureBusStation = BusStationItem().apply {
                                busStationName = "主线上车站"
                                latLonPoint = LatLonPoint(30.0, 120.0)
                            }
                            arrivalBusStation = BusStationItem().apply {
                                busStationName = "主线下车站"
                                latLonPoint = LatLonPoint(30.1, 120.1)
                            }
                        },
                        RouteBusLineItem().apply {
                            busLineName = "2路"
                            busLineId = "l2"
                            passStationNum = 4
                            departureBusStation = BusStationItem().apply {
                                busStationName = "备选上车站"
                                latLonPoint = LatLonPoint(30.5, 120.5)
                            }
                            arrivalBusStation = BusStationItem().apply {
                                busStationName = "备选下车站"
                                latLonPoint = LatLonPoint(30.6, 120.6)
                            }
                        }
                    )
                }
            )
        }

        val scheme = AmapSdkTransitService.convertBusPathToScheme(
            path = busPath,
            originName = "起",
            destName = "终",
            cityName = "杭州"
        )

        val leg = scheme?.group?.legs?.get(0)
        assertEquals("主线上车站", leg?.fromStopName)
        assertEquals("主线下车站", leg?.toStopName)
        assertEquals(30.0, leg?.fromStopLat ?: 0.0, 1e-6)
        assertEquals(2, leg?.candidateLines?.size)
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
