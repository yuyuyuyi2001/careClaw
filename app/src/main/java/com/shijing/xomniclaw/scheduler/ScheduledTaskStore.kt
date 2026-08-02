package com.shijing.xomniclaw.scheduler

import android.util.Log
import com.google.gson.GsonBuilder
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 定时任务存储。
 *
 * 使用独立 JSON 文件保存任务，避免把系统级定时能力和现有 session / cron 数据混写。
 */
class ScheduledTaskStore(
    private val storePath: String = DEFAULT_STORE_PATH
) {
    companion object {
        private const val TAG = "ScheduledTaskStore"
        const val DEFAULT_STORE_PATH = "/sdcard/.xomniclaw/config/scheduled_tasks.json"
    }

    private val lock = ReentrantLock()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun list(): List<ScheduledTask> = lock.withLock {
        loadInternal().tasks
    }

    fun get(taskId: String): ScheduledTask? = lock.withLock {
        loadInternal().tasks.find { it.id == taskId }
    }

    fun upsert(task: ScheduledTask): ScheduledTask = lock.withLock {
        val current = loadInternal().tasks.toMutableList()
        val index = current.indexOfFirst { it.id == task.id }
        if (index >= 0) {
            current[index] = task
        } else {
            current.add(task)
        }
        saveInternal(ScheduledTaskStoreFile(tasks = current))
        task
    }

    fun replaceAll(tasks: List<ScheduledTask>) = lock.withLock {
        saveInternal(ScheduledTaskStoreFile(tasks = tasks))
    }

    fun delete(taskId: String): Boolean = lock.withLock {
        val current = loadInternal().tasks.toMutableList()
        val removed = current.removeIf { it.id == taskId }
        if (removed) {
            saveInternal(ScheduledTaskStoreFile(tasks = current))
        }
        removed
    }

    private fun loadInternal(): ScheduledTaskStoreFile {
        return try {
            val file = File(storePath)
            if (!file.exists()) {
                val empty = ScheduledTaskStoreFile()
                saveInternal(empty)
                return empty
            }

            gson.fromJson(file.readText(), ScheduledTaskStoreFile::class.java) ?: ScheduledTaskStoreFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scheduled tasks", e)
            ScheduledTaskStoreFile()
        }
    }

    private fun saveInternal(store: ScheduledTaskStoreFile) {
        val file = File(storePath)
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(store))
    }
}
