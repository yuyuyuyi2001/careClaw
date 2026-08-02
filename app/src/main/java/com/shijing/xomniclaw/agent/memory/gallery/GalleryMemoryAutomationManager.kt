package com.shijing.xomniclaw.agent.memory.gallery

import android.content.Context
import com.shijing.xomniclaw.scheduler.ScheduledTask
import com.shijing.xomniclaw.scheduler.ScheduledTaskEditDraft
import com.shijing.xomniclaw.scheduler.ScheduledTaskManager

/**
 * 相册记忆后台自动同步任务维护器。
 *
 * 负责把“状态页上的配置”映射成一个稳定的 interval 定时任务。
 */
class GalleryMemoryAutomationManager(
    context: Context,
    private val settingsStore: GalleryMemorySettingsStore = GalleryMemorySettingsStore()
) {
    companion object {
        const val TASK_NAME = "相册记忆自动同步"
        const val TASK_INSTRUCTION =
            "调用 gallery_memory 工具，参数 action=sync、update_profile=true。同步相册中的新增图片记忆，并更新用户画像；仅做增量扫描。"
    }

    private val taskManager = ScheduledTaskManager(context)

    fun applySettings(settings: GalleryMemorySettings): GalleryMemorySettings {
        if (!settings.featureEnabled) {
            deleteManagedTask(settings.automationTaskId)
            val updated = settings.copy(automationTaskId = null)
            settingsStore.save(updated)
            return updated
        }

        val existingTask = resolveManagedTask(settings.automationTaskId)
        val updatedTaskId = if (existingTask == null) {
            taskManager.createIntervalTask(
                name = TASK_NAME,
                instruction = TASK_INSTRUCTION,
                intervalMinutes = settings.scanIntervalMinutes,
                exact = true,
                allowWhileIdle = true
            ).id
        } else {
            taskManager.updateTask(
                ScheduledTaskEditDraft.fromTask(existingTask).copy(
                    name = TASK_NAME,
                    instruction = TASK_INSTRUCTION,
                    repeat = ScheduledTask.REPEAT_INTERVAL,
                    intervalMinutesText = settings.scanIntervalMinutes.toString(),
                    enabled = true,
                    exact = true,
                    allowWhileIdle = true
                )
            )
            existingTask.id
        }

        val updated = settings.copy(automationTaskId = updatedTaskId)
        settingsStore.save(updated)
        return updated
    }

    fun resolveManagedTask(taskId: String?): ScheduledTask? {
        val byId = taskId?.let { taskManager.getTask(it) }
        if (byId != null) {
            return byId
        }

        return taskManager.listTasks().firstOrNull(::isManagedTask)
    }

    private fun deleteManagedTask(taskId: String?) {
        resolveManagedTask(taskId)?.let { taskManager.cancelTask(it.id) }
    }

    private fun isManagedTask(task: ScheduledTask): Boolean {
        return task.name == TASK_NAME || task.instruction == TASK_INSTRUCTION
    }
}
