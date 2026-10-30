package com.trackr.data.repository

import com.trackr.domain.model.ScheduledReminder
import kotlinx.coroutines.flow.StateFlow

/**
 * 定时通勤提醒仓库接口
 */
interface IScheduledReminderRepository {
    /**
     * 当前所有定时提醒列表流
     */
    val reminders: StateFlow<List<ScheduledReminder>>

    /**
     * 新增或更新一条定时提醒
     */
    suspend fun saveReminder(reminder: ScheduledReminder)

    /**
     * 删除指定 ID 的定时提醒
     */
    suspend fun deleteReminder(id: String)

    /**
     * 切换指定提醒的启用状态
     */
    suspend fun toggleReminder(id: String, enabled: Boolean)

    /**
     * 根据 ID 获取提醒对象
     */
    fun getReminderById(id: String): ScheduledReminder?
}
