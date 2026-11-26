package com.trackr.ui.screens.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.trackr.core.domain.platform.SpeechInput
import kotlinx.coroutines.CompletableDeferred

/** 识别界面上的提示 */
internal const val SPEECH_PROMPT = "说线路号或站名，比如「325」"

/** 系统语音输入的 Intent：中文、按搜索词识别、只要一条结果 */
internal fun speechIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
    .putExtra(RecognizerIntent.EXTRA_PROMPT, SPEECH_PROMPT)
    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

/**
 * 手机上有没有能接 [RecognizerIntent.ACTION_RECOGNIZE_SPEECH] 的识别界面。
 * Android 11 起要在清单 `<queries>` 里声明这个 action 才查得到（`:androidApp` 的 AndroidManifest.xml）。
 */
internal fun isSpeechRecognizerAvailable(context: Context): Boolean =
    context.packageManager.queryIntentActivities(speechIntent(), 0).isNotEmpty()

/** 从识别界面的返回里取文字：取第一条不空的，去掉首尾空白和句末标点；取消或没听清为 null */
internal fun recognizedText(result: ActivityResult): String? {
    if (result.resultCode != Activity.RESULT_OK) return null
    return result.data
        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        ?.map { it.trim().trimEnd('。', '.', '，', ',', '？', '?', '！', '!').trim() }
        ?.firstOrNull { it.isNotEmpty() }
}

/**
 * [SpeechInput] 的 Android 实现：[listen] 打开系统语音输入，等它返回识别出的文字。
 * 同时只等一次：再点一次话筒，前一次的等待返回 null。转屏或进程被杀时正在等的那次作废（返回的结果没人要），用户再点一次就行。
 */
internal class RecognizerSpeechInput : SpeechInput {
    private var launch: ((Intent) -> Unit)? = null
    private var pending: CompletableDeferred<String?>? = null

    /** 打不开识别界面（手机上的识别服务被卸载或停用）时调 */
    var onUnavailable: () -> Unit = {}

    fun attach(launch: (Intent) -> Unit) {
        this.launch = launch
    }

    fun detach() {
        launch = null
        pending?.complete(null)
        pending = null
    }

    fun deliver(result: ActivityResult) {
        pending?.complete(recognizedText(result))
        pending = null
    }

    override suspend fun listen(): String? {
        val launcher = launch ?: return null
        pending?.complete(null)
        val deferred = CompletableDeferred<String?>()
        pending = deferred
        try {
            launcher(speechIntent())
        } catch (_: ActivityNotFoundException) {
            pending = null
            onUnavailable()
            return null
        }
        return deferred.await()
    }
}

@Composable
actual fun rememberSpeechInput(): SpeechInput? {
    val context = LocalContext.current
    var available by remember(context) { mutableStateOf(isSpeechRecognizerAvailable(context)) }
    val input = remember { RecognizerSpeechInput() }
    input.onUnavailable = { available = false }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { input.deliver(it) }
    DisposableEffect(input, launcher) {
        input.attach { intent -> launcher.launch(intent) }
        onDispose { input.detach() }
    }
    return input.takeIf { available }
}
