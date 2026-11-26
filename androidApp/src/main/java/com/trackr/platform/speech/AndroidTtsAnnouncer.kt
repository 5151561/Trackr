package com.trackr.platform.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.trackr.core.domain.platform.Announcer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 「读给我听」的 Android 实现（重构计划 §8.6）：系统 TextToSpeech，不加新依赖。
 *
 * - 懒初始化：第一次 [speak] 才创建引擎（不在 `Application.onCreate` 里建，构造是异步回调，不挡主线程）。
 *   初始化失败、不支持中文或 [initTimeoutMillis] 内没回调，就当读不了，[speak] 返回 false，引擎随即释放；
 *   下次再点会重新试（用户可能刚装了朗读引擎）。
 * - 音频焦点：读之前申请 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`（音乐压低不暂停），读完、出错或 [stop] 时归还。
 * - 释放：最后一次读完 [idleShutdownMillis]（5 分钟）没再用就 `shutdown()`；Koin 关闭时调 [shutdown]。
 *
 * 引擎和焦点只在 [mainDispatcher] 上读写；[stop]、[shutdown] 要在主线程调（ViewModel.onCleared、Koin 关闭都在主线程）。
 */
class AndroidTtsAnnouncer(
    context: Context,
    /** 进程级作用域（Koin 的 app scope），放空闲释放的计时 */
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val initTimeoutMillis: Long = INIT_TIMEOUT_MILLIS,
    private val idleShutdownMillis: Long = IDLE_SHUTDOWN_MILLIS,
    /** 建引擎（测试可替换）；默认用系统默认引擎 */
    private val createEngine: (Context, TextToSpeech.OnInitListener) -> TextToSpeech = { ctx, listener ->
        TextToSpeech(ctx, listener)
    }
) : Announcer {

    private val appContext: Context = context.applicationContext ?: context
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)

    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var focusRequest: AudioFocusRequest? = null
    private var idleJob: Job? = null
    private var utteranceSeq = 0

    /** 当前引擎已经设好中文、挂好进度回调 */
    private var languageReady = false

    /** 当前引擎（测试用）；没建或已释放为 null */
    internal val currentEngine: TextToSpeech? get() = engine

    override suspend fun speak(text: String): Boolean = withContext(mainDispatcher) {
        val sentence = text.trim()
        if (sentence.isEmpty()) return@withContext true
        idleJob?.cancel()
        val tts = awaitEngine()
        if (tts == null) {
            release()
            return@withContext false
        }
        requestFocus()
        val id = "trackr_say_${++utteranceSeq}"
        if (tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            abandonFocus()
            scheduleIdleShutdown()
            return@withContext false
        }
        true
    }

    override fun stop() {
        engine?.stop()
        abandonFocus()
        if (engine != null) scheduleIdleShutdown()
    }

    /** 立即释放引擎和音频焦点（Koin 关闭时调）；之后再 [speak] 会重新初始化 */
    fun shutdown() {
        idleJob?.cancel()
        idleJob = null
        release()
    }

    /** 拿到能用的引擎；读不了返回 null */
    private suspend fun awaitEngine(): TextToSpeech? {
        val pending = ready ?: CompletableDeferred<Boolean>().also { deferred ->
            ready = deferred
            // 初始化回调可能在构造函数里同步回来（绑定失败时），所以先把 deferred 放好再建
            engine = createEngine(appContext) { status -> deferred.complete(status == TextToSpeech.SUCCESS) }
        }
        val initialized = withTimeoutOrNull(initTimeoutMillis) { pending.await() } ?: false
        val tts = engine ?: return null
        if (!initialized) return null
        if (!languageReady) {
            val result = tts.setLanguage(Locale.CHINA)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return null
            tts.setOnUtteranceProgressListener(progressListener)
            languageReady = true
        }
        return tts
    }

    /** 读完 / 出错时归还焦点并开始空闲计时；回调在引擎的线程上，切回主线程再动 */
    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = onUtteranceFinished()

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onUtteranceFinished()

        override fun onError(utteranceId: String?, errorCode: Int) = onUtteranceFinished()

        // 被下一句打断（QUEUE_FLUSH）或 stop()：焦点由新的一句或 stop() 处理
        override fun onStop(utteranceId: String?, interrupted: Boolean) = Unit
    }

    private fun onUtteranceFinished() {
        scope.launch(mainDispatcher) {
            abandonFocus()
            scheduleIdleShutdown()
        }
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
            .also { focusRequest = it }
        manager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        audioManager?.abandonAudioFocusRequest(request)
    }

    private fun scheduleIdleShutdown() {
        idleJob?.cancel()
        idleJob = scope.launch(mainDispatcher) {
            delay(idleShutdownMillis)
            idleJob = null
            release()
        }
    }

    private fun release() {
        abandonFocus()
        engine?.let { tts ->
            tts.stop()
            tts.shutdown()
        }
        engine = null
        languageReady = false
        ready = null
    }

    companion object {
        /** 等引擎初始化回调最多这么久 */
        const val INIT_TIMEOUT_MILLIS: Long = 5_000L

        /** 读完后这么久没再用就释放引擎 */
        const val IDLE_SHUTDOWN_MILLIS: Long = 5 * 60_000L
    }
}
