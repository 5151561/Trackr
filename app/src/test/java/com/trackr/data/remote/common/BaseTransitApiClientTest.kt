package com.trackr.data.remote.common

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class BaseTransitApiClientTest {

    private class TestTransitApiClient(httpClient: OkHttpClient) : BaseTransitApiClient(httpClient) {
        suspend fun testExecuteHttpCall(request: Request): Response = executeHttpCall(request)
        suspend fun testExecuteJson(request: Request) = executeJson(request)
        fun testIsSubwayStationName(name: String) = isSubwayStationName(name)
        fun testCleanSubwaySuffix(name: String) = cleanSubwaySuffix(name)
        fun testResolveDistance(
            serverDistance: Int?,
            userLat: Double?,
            userLng: Double?,
            stationLat: Double,
            stationLng: Double
        ) = resolveDistance(serverDistance, userLat, userLng, stationLat, stationLng)
        fun testSanitizeBusPlate(raw: String?) = sanitizeBusPlate(raw)
    }

    @Test
    fun testSubwayStationNameDetectionAndCleaning() {
        val client = TestTransitApiClient(OkHttpClient())

        // Subway indicators
        assertTrue(client.testIsSubwayStationName("湖滨中路（地铁站）"))
        assertTrue(client.testIsSubwayStationName("湖滨中路(地铁站)"))
        assertTrue(client.testIsSubwayStationName("湖滨中路站（M）"))
        assertTrue(client.testIsSubwayStationName("湖滨中路站(M)"))

        // Ground stations (should not be detected as pure subway)
        assertFalse(client.testIsSubwayStationName("湖滨中路"))
        assertFalse(client.testIsSubwayStationName("高新园地铁站"))
        assertFalse(client.testIsSubwayStationName("人民广场地铁站公交站"))

        // Cleaning suffix
        assertEquals("湖滨中路", client.testCleanSubwaySuffix("湖滨中路（地铁站）"))
        assertEquals("湖滨中路", client.testCleanSubwaySuffix("湖滨中路(地铁站)"))
        assertEquals("湖滨中路站", client.testCleanSubwaySuffix("湖滨中路站（M）"))
        assertEquals("湖滨中路站", client.testCleanSubwaySuffix("湖滨中路站(M)"))
    }

    @Test
    fun testResolveDistance_prefersServerDistance() {
        val client = TestTransitApiClient(OkHttpClient())

        // Server distance > 0 is preferred
        val dist = client.testResolveDistance(
            serverDistance = 350,
            userLat = 31.23,
            userLng = 121.47,
            stationLat = 31.24,
            stationLng = 121.48
        )
        assertEquals(350, dist)
    }

    @Test
    fun testResolveDistance_fallsBackToCoordinatesMath() {
        val client = TestTransitApiClient(OkHttpClient())

        // Server distance is null, calculates from user & station coords
        val dist = client.testResolveDistance(
            serverDistance = null,
            userLat = 31.2300,
            userLng = 121.4700,
            stationLat = 31.2350,
            stationLng = 121.4700
        )
        assertNotNull(dist)
        assertTrue(dist!! in 500..600)

        // Server distance <= 0, calculates from coords
        val distZero = client.testResolveDistance(
            serverDistance = 0,
            userLat = 31.2300,
            userLng = 121.4700,
            stationLat = 31.2350,
            stationLng = 121.4700
        )
        assertNotNull(distZero)
        assertTrue(distZero!! in 500..600)

        // No user coords available -> returns null
        val distNoCoords = client.testResolveDistance(
            serverDistance = null,
            userLat = null,
            userLng = null,
            stationLat = 31.2350,
            stationLng = 121.4700
        )
        assertNull(distNoCoords)
    }

    @Test
    fun testSanitizeBusPlate() {
        val client = TestTransitApiClient(OkHttpClient())

        assertEquals("沪A·D8866", client.testSanitizeBusPlate("沪A·D8866"))
        assertEquals("417", client.testSanitizeBusPlate("417"))
        assertEquals("K102", client.testSanitizeBusPlate("K102"))
        // Internal hashes should be sanitized to empty
        assertEquals("", client.testSanitizeBusPlate("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"))
        assertEquals("", client.testSanitizeBusPlate(null))
    }

    @Test
    fun testExecuteHttpCallAndExecuteJson() = runBlocking {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            when (req.url.encodedPath) {
                "/success" -> Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"status": "1", "data": "hello"}""".toResponseBody("application/json".toMediaType()))
                    .build()
                "/error" -> Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("""{"error": "fail"}""".toResponseBody("application/json".toMediaType()))
                    .build()
                else -> Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val client = TestTransitApiClient(okHttpClient)

        val successReq = Request.Builder().url("https://example.com/success").build()
        val json = client.testExecuteJson(successReq)
        assertEquals("1", json.getString("status"))
        assertEquals("hello", json.getString("data"))

        val errorReq = Request.Builder().url("https://example.com/error").build()
        try {
            client.testExecuteJson(errorReq)
            fail("Expected IOException for HTTP 500")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("500") == true)
        }
    }
}
