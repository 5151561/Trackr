package com.trackr.platform.speech

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

/**
 * 「读给我听」的 Android 实现（第 33 步，重构计划 §8.6、§8.7）：Robolectric 的 ShadowTextToSpeech 模拟引擎。
 * 初始化回调由测试手动触发；没装引擎 / 不支持中文 / 超时都要降级为 false（页面据此提示用户）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AndroidTtsAnnouncerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audio get() = shadowOf(context.getSystemService(AudioManager::class.java))

    @Before
    fun setUp() {
        ShadowTextToSpeech.reset()
    }

    @After
    fun tearDown() {
        ShadowTextToSpeech.reset()
    }

    private fun TestScope.newAnnouncer() = AndroidTtsAnnouncer(
        context = context,
        scope = backgroundScope,
        mainDispatcher = UnconfinedTestDispatcher(testScheduler)
    )

    /** 开始读，并让引擎按 [status] 回调初始化结果 */
    private suspend fun TestScope.speakWithInit(announcer: AndroidTtsAnnouncer, text: String, status: Int): Boolean {
        val result = async { announcer.speak(text) }
        runCurrent()
        val engine = checkNotNull(announcer.currentEngine)
        shadowOf(engine).onInitListener.onInit(status)
        return result.await()
    }

    @Test
    fun noEngineUntilFirstSpeak() = runTest {
        val announcer = newAnnouncer()
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertNull(announcer.currentEngine)

        // 空句子不建引擎
        assertTrue(announcer.speak("  "))
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
    }

    @Test
    fun initSuccess_speaksChineseWithTransientDuckFocus_thenReleasesFocus() = runTest {
        ShadowTextToSpeech.addLanguageAvailability(Locale.CHINA)
        val announcer = newAnnouncer()

        assertTrue(speakWithInit(announcer, "325路，还有 2 站，大约 4 分钟到科技园站", TextToSpeech.SUCCESS))

        val engine = shadowOf(announcer.currentEngine)
        assertEquals("325路，还有 2 站，大约 4 分钟到科技园站", engine.lastSpokenText)
        assertEquals(TextToSpeech.QUEUE_FLUSH, engine.queueMode)
        assertEquals(Locale.CHINA, engine.currentLanguage)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, audio.lastAudioFocusRequest.durationHint)
        assertNull(audio.lastAbandonedAudioFocusRequest)

        // 读完（引擎回调 onDone）归还焦点
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(audio.lastAbandonedAudioFocusRequest)
        assertFalse(engine.isShutdown)
    }

    @Test
    fun secondSpeak_reusesEngine_andInterruptsPrevious() = runTest {
        ShadowTextToSpeech.addLanguageAvailability(Locale.CHINA)
        val announcer = newAnnouncer()
        assertTrue(speakWithInit(announcer, "第一句", TextToSpeech.SUCCESS))
        val first = announcer.currentEngine

        assertTrue(announcer.speak("第二句"))

        assertTrue(first === announcer.currentEngine)
        assertEquals(listOf("第一句", "第二句"), shadowOf(first).spokenTextList)
        assertEquals(TextToSpeech.QUEUE_FLUSH, shadowOf(first).queueMode)
    }

    @Test
    fun initFailure_returnsFalse_releasesEngine_andRetriesNextTime() = runTest {
        ShadowTextToSpeech.addLanguageAvailability(Locale.CHINA)
        val announcer = newAnnouncer()

        assertFalse(speakWithInit(announcer, "325路", TextToSpeech.ERROR))
        val failed = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertTrue(shadowOf(failed).isShutdown)
        assertNull(announcer.currentEngine)

        // 再点一次会重新建引擎（用户可能刚装好朗读引擎）
        assertTrue(speakWithInit(announcer, "325路", TextToSpeech.SUCCESS))
        assertNotSame(failed, announcer.currentEngine)
    }

    @Test
    fun chineseNotSupported_returnsFalse() = runTest {
        // 没登记任何可用语言：setLanguage 返回 LANG_NOT_SUPPORTED
        val announcer = newAnnouncer()

        assertFalse(speakWithInit(announcer, "325路", TextToSpeech.SUCCESS))

        assertTrue(shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).isShutdown)
        assertNull(audio.lastAudioFocusRequest)
    }

    @Test
    fun initNeverCallsBack_timesOutAsUnavailable() = runTest {
        val announcer = newAnnouncer()
        val result = async { announcer.speak("325路") }
        runCurrent()
        assertNotNull(announcer.currentEngine)

        advanceTimeBy(AndroidTtsAnnouncer.INIT_TIMEOUT_MILLIS + 1)

        assertFalse(result.await())
        assertNull(announcer.currentEngine)
        assertTrue(shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).isShutdown)
    }

    @Test
    fun idleFiveMinutesAfterDone_shutsDownEngine() = runTest {
        ShadowTextToSpeech.addLanguageAvailability(Locale.CHINA)
        val announcer = newAnnouncer()
        assertTrue(speakWithInit(announcer, "325路", TextToSpeech.SUCCESS))
        val engine = announcer.currentEngine
        shadowOf(Looper.getMainLooper()).idle()

        advanceTimeBy(AndroidTtsAnnouncer.IDLE_SHUTDOWN_MILLIS - 1)
        assertFalse(shadowOf(engine).isShutdown)

        advanceTimeBy(2)
        assertTrue(shadowOf(engine).isShutdown)
        assertNull(announcer.currentEngine)
    }

    @Test
    fun stop_stopsSpeakingAndReleasesFocus() = runTest {
        ShadowTextToSpeech.addLanguageAvailability(Locale.CHINA)
        val announcer = newAnnouncer()
        assertTrue(speakWithInit(announcer, "325路", TextToSpeech.SUCCESS))
        val engine = announcer.currentEngine

        announcer.stop()

        assertTrue(shadowOf(engine).isStopped)
        assertNotNull(audio.lastAbandonedAudioFocusRequest)
        assertFalse(shadowOf(engine).isShutdown)
    }

    @Test
    fun shutdown_releasesEngine() = runTest {
        ShadowTextToSpeech.addLanguageAvailability(Locale.CHINA)
        val announcer = newAnnouncer()
        assertTrue(speakWithInit(announcer, "325路", TextToSpeech.SUCCESS))
        val engine = announcer.currentEngine

        announcer.shutdown()

        assertTrue(shadowOf(engine).isShutdown)
        assertNull(announcer.currentEngine)
        // stop() 在没引擎时什么都不做
        announcer.stop()
    }
}
