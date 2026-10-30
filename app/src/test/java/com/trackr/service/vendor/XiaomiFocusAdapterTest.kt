package com.trackr.service.vendor

import android.app.Application
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.trackr.domain.model.TrackingSessionState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class XiaomiFocusAdapterTest {

    private lateinit var context: Application

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testBuildJsonPayload_inTransitState() {
        val state = TrackingSessionState(
            isActive = true,
            lineName = "37路",
            endStopName = "扬子江路",
            targetStopName = "文博城",
            targetOrder = 10,
            startTrackingOrder = 1,
            currentBusOrder = 7,
            currentBusProgress = 0.5f,
            stopsRemaining = 3,
            etaSeconds = 360,
            statusSummary = "正在在途追踪"
        )

        val jsonString = XiaomiFocusAdapter.buildJsonPayload(state)
        assertTrue("JSON 字节大小必须小于 3072 字节", jsonString.toByteArray(Charsets.UTF_8).size <= XiaomiFocusAdapter.MAX_JSON_BYTES)

        val root = JSONObject(jsonString)
        assertTrue(root.has("param_v2"))
        val paramV2 = root.getJSONObject("param_v2")

        assertEquals(1, paramV2.getInt("protocol"))
        assertEquals("bus", paramV2.getString("type"))
        assertTrue(paramV2.getBoolean("updatable"))

        val capsule = paramV2.getJSONObject("capsule")
        assertEquals("剩3站", capsule.getString("text"))
        assertEquals(0, capsule.getInt("status"))

        val baseInfo = paramV2.getJSONObject("baseInfo")
        assertTrue(baseInfo.getString("title").contains("37路"))
        assertTrue(baseInfo.getString("content").contains("文博城"))

        val progress = paramV2.getJSONObject("progress")
        assertEquals(900, progress.getInt("max"))
        assertTrue(progress.getInt("current") > 0)
    }

    @Test
    fun testBuildJsonPayload_arrivedState() {
        val state = TrackingSessionState(
            isActive = true,
            lineName = "37路",
            endStopName = "扬子江路",
            targetStopName = "文博城",
            targetOrder = 10,
            startTrackingOrder = 1,
            currentBusOrder = 10,
            currentBusProgress = 1.0f,
            isBusArrivedAtStop = true,
            stopsRemaining = 0,
            etaSeconds = 0
        )

        val jsonString = XiaomiFocusAdapter.buildJsonPayload(state)
        val root = JSONObject(jsonString)
        val paramV2 = root.getJSONObject("param_v2")
        val capsule = paramV2.getJSONObject("capsule")

        assertEquals("已到站", capsule.getString("text"))
        assertEquals(1, capsule.getInt("status"))

        val progress = paramV2.getJSONObject("progress")
        assertEquals(progress.getInt("max"), progress.getInt("current"))
    }

    @Test
    fun testBuildJsonPayload_approachingState_showsApproachingText() {
        val state = TrackingSessionState(
            isActive = true,
            lineName = "37路",
            targetStopName = "文博城",
            targetOrder = 10,
            startTrackingOrder = 7,
            currentBusOrder = 10,
            currentBusProgress = 0.5f,
            stopsRemaining = 0,
            etaSeconds = 80,
            isBusArrivedAtStop = false
        )

        val jsonString = XiaomiFocusAdapter.buildJsonPayload(state)
        val root = JSONObject(jsonString)
        val paramV2 = root.getJSONObject("param_v2")
        val capsule = paramV2.getJSONObject("capsule")

        // 验证即将在途进站时胶囊文案为“即将进站”，绝不显示“剩0站”
        assertEquals("即将进站", capsule.getString("text"))
        assertEquals(0, capsule.getInt("status"))

        val progress = paramV2.getJSONObject("progress")
        assertEquals(300, progress.getInt("max"))
        assertEquals(250, progress.getInt("current"))
    }

    @Test
    fun testBuildJsonPayload_noBusState() {
        val state = TrackingSessionState(
            isActive = true,
            lineName = "37路",
            targetStopName = "文博城",
            targetOrder = 5,
            stopsRemaining = -1,
            statusSummary = "暂无在途车辆"
        )

        val jsonString = XiaomiFocusAdapter.buildJsonPayload(state)
        val root = JSONObject(jsonString)
        val paramV2 = root.getJSONObject("param_v2")
        val capsule = paramV2.getJSONObject("capsule")

        assertEquals("追踪中", capsule.getString("text"))
        assertEquals(0, capsule.getInt("status"))
    }

    @Test
    fun testAttachFocusParams_injectsExtraKey() {
        val state = TrackingSessionState(
            isActive = true,
            lineName = "1路",
            targetStopName = "人民广场",
            stopsRemaining = 2,
            targetOrder = 8
        )

        val builder = NotificationCompat.Builder(context, "test_channel")
        XiaomiFocusAdapter.attachFocusParams(builder, state)

        val extraJson = builder.extras.getString(XiaomiFocusAdapter.EXTRA_KEY)
        assertNotNull("extras 中必须包含 miui.focus.param", extraJson)

        val root = JSONObject(extraJson!!)
        assertTrue(root.has("param_v2"))
        assertEquals("剩2站", root.getJSONObject("param_v2").getJSONObject("capsule").getString("text"))
    }
}
