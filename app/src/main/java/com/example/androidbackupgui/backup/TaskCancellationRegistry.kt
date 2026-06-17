package com.example.androidbackupgui.backup

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object TaskCancellationRegistry {

    private val registrations = ConcurrentHashMap<String, Registration>()

    data class Registration(
        val cancel: () -> Unit,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    )

    fun register(taskId: String, cancel: () -> Unit): Registration {
        val reg = Registration(cancel)
        registrations[taskId] = reg
        return reg
    }

    fun registerJob(taskId: String, job: Job): Registration {
        return register(taskId) { job.cancel() }
    }

    fun cancel(taskId: String): Boolean {
        val reg = registrations[taskId] ?: return false
        if (reg.cancelled.compareAndSet(false, true)) {
            try {
                reg.cancel()
            } catch (_: Exception) {
            }
            return true
        }
        return false
    }

    fun isCancelled(taskId: String): Boolean {
        return registrations[taskId]?.cancelled?.get() == true
    }

    fun throwIfCancelled(taskId: String) {
        if (isCancelled(taskId)) {
            throw CancellationException("Task $taskId was cancelled")
        }
    }

    fun unregister(taskId: String) {
        registrations.remove(taskId)
    }

    class CancellationException(message: String) : Exception(message)
}
