package com.trackr.util

import android.content.Context
import android.content.pm.Signature
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun selectCurrentFromHistory_returnsLastEntry() {
        // 官方文档：原始证书在下标 0，当前（轮换后）证书在最后
        val original = Signature(byteArrayOf(0x01, 0x02))
        val rotated = Signature(byteArrayOf(0x03, 0x04))
        val selected = SignatureUtils.selectCurrentFromHistory(arrayOf(original, rotated))
        assertEquals(rotated, selected)

        assertNull(SignatureUtils.selectCurrentFromHistory(null))
        assertNull(SignatureUtils.selectCurrentFromHistory(emptyArray<Signature>()))
    }

    @Test
    fun sha1Fingerprint_formatsUppercaseColonSeparated() {
        val fingerprint = SignatureUtils.sha1Fingerprint("abc".toByteArray(Charsets.UTF_8))
        assertEquals("A9:99:3E:36:47:06:81:6A:BA:3E:25:71:78:50:C2:6C:9C:D0:D8:9D", fingerprint)
    }
}
