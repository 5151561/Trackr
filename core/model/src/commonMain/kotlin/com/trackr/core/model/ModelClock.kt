package com.trackr.core.model

import kotlin.time.Clock

/**
 * 模型时间字段的默认值（墙钟毫秒）。
 *
 * 只给 @Serializable 模型的默认参数用：旧数据里缺这个字段时反序列化要能补上，
 * 所以默认值不能去掉。业务逻辑里的「现在」一律由调用方传入（重构计划 §2.1）。
 */
internal fun defaultNowMillis(): Long = Clock.System.now().toEpochMilliseconds()
