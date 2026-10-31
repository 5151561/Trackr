package com.trackr.util

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.trackr.domain.model.TransitCodeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TransitCodeHelperTest {

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext<Application>()
    }

    @Test
    fun testOpenAlipayTransitCode_createsCorrectIntent() {
        val result = TransitCodeHelper.openAlipayTransitCode(app)
        assertTrue(result)

        val startedIntent = shadowOf(app).nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(Intent.ACTION_VIEW, startedIntent.action)
        assertEquals(Uri.parse(TransitCodeHelper.SCHEME_ALIPAY_TRANSIT), startedIntent.data)
        assertTrue((startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun testOpenUnionPayTransitCode_createsCorrectIntent() {
        val result = TransitCodeHelper.openUnionPayTransitCode(app)
        assertTrue(result)

        val startedIntent = shadowOf(app).nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(Intent.ACTION_VIEW, startedIntent.action)
        assertEquals(Uri.parse(TransitCodeHelper.SCHEME_UNIONPAY_TRANSIT), startedIntent.data)
        assertTrue((startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun testOpenTransitCode_dispatchesProperly() {
        // 1. Alipay
        TransitCodeHelper.openTransitCode(app, TransitCodeProvider.ALIPAY)
        val alipayIntent = shadowOf(app).nextStartedActivity
        assertNotNull(alipayIntent)
        assertEquals(Uri.parse(TransitCodeHelper.SCHEME_ALIPAY_TRANSIT), alipayIntent.data)

        // 2. UnionPay
        TransitCodeHelper.openTransitCode(app, TransitCodeProvider.UNIONPAY)
        val unionpayIntent = shadowOf(app).nextStartedActivity
        assertNotNull(unionpayIntent)
        assertEquals(Uri.parse(TransitCodeHelper.SCHEME_UNIONPAY_TRANSIT), unionpayIntent.data)

        // 3. ASK_ALWAYS returns false without launching
        val askAlwaysResult = TransitCodeHelper.openTransitCode(app, TransitCodeProvider.ASK_ALWAYS)
        assertFalse(askAlwaysResult)
    }

    @Test
    fun testTransitCodeProvider_fromId() {
        assertEquals(TransitCodeProvider.ALIPAY, TransitCodeProvider.fromId("alipay"))
        assertEquals(TransitCodeProvider.UNIONPAY, TransitCodeProvider.fromId("unionpay"))
        assertEquals(TransitCodeProvider.ASK_ALWAYS, TransitCodeProvider.fromId("ask_always"))
        assertEquals(TransitCodeProvider.ASK_ALWAYS, TransitCodeProvider.fromId("unknown"))
        assertEquals(TransitCodeProvider.ASK_ALWAYS, TransitCodeProvider.fromId(null))
    }
}
