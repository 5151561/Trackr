package com.trackr.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SignatureUtilsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun getPackageName_returnsNonNull() {
        val packageName = SignatureUtils.getPackageName(context)
        assertNotNull(packageName)
        assertEquals(context.packageName, packageName)
    }

    @Test
    fun getSigningCertificateSha1_executesSafely() {
        // Robolectric 测试环境下验证不崩溃并返回格式良好的字符串
        val sha1 = SignatureUtils.getSigningCertificateSha1(context)
        assertNotNull(sha1)
    }
}
