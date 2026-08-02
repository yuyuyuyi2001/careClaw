package com.shijing.xomniclaw.agent.memory.evolution

import android.content.Context
import com.shijing.xomniclaw.scheduler.ScheduledTask
import com.shijing.xomniclaw.scheduler.ScheduledTaskEditDraft
import com.shijing.xomniclaw.scheduler.ScheduledTaskManager

/**
 * 全局记忆进化定时任务维护器。
 *
 * 与相册记忆自动同步一样，使用稳定的 interval 定时任务统一批处理记忆更新。
 */
class MemoryEvolutionAutomationManager(
    context: Context,
    private val settingsStore: MemoryEvolutionSettingsStore = MemoryEvolutionSettingsStore()
) {
    companion object {
        const val TASK_NAME = "全局记忆进化"
        const val TASK_INSTRUCTION =
            "调用 memory_evolution 工具，参数 action=run。处理待沉淀的 X-OmniClaw 任务记忆，更新 MEMORY.md，并重建 USER-PROFILE.md。"
    }

    private val taskManager = ScheduledTaskManager(context)

    fun applySettings(settings: MemoryEvolutionSettings): MemoryEvolutionSettings {
        if (!settings.enabled) {
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
                intervalMinutes = settings.intervalMinutes,
                exact = true,
                allowWhileIdle = true
            ).id
        } else {
            taskManager.updateTask(
                ScheduledTaskEditDraft.fromTask(existingTask).copy(
                    name = TASK_NAME,
                    instruction = TASK_INSTRUCTION,
                    repeat = ScheduledTask.REPEAT_INTERVAL,
                    intervalMinutesText = settings.intervalMinutes.toString(),
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

    fun ensureDefaultTask(): MemoryEvolutionSettings {
        return applySettings(settingsStore.load())
    }

    private fun deleteManagedTask(taskId: String?) {
        resolveManagedTask(taskId)?.let { taskManager.cancelTask(it.id) }
    }

    private fun isManagedTask(task: ScheduledTask): Boolean {
        return task.name == TASK_NAME || task.instruction == TASK_INSTRUCTION
    }
}
