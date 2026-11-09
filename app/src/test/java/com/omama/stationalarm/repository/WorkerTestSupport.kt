package com.omama.stationalarm.repository

import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.Executor

/**
 * Builds WorkerParameters directly because androidx.work:work-testing (TestListenableWorkerBuilder) is not a
 * test dependency. Executors run inline; the progress/foreground updaters are never used by these workers.
 */
internal fun workerParams(input: Data = Data.EMPTY, runAttemptCount: Int = 0): WorkerParameters {
    val inline = Executor { it.run() }
    return WorkerParameters(
        UUID.randomUUID(), input, emptyList(), WorkerParameters.RuntimeExtras(), runAttemptCount, 0,
        inline, WorkManagerTaskExecutor(inline), WorkerFactory.getDefaultWorkerFactory(),
        unusedProxy(ProgressUpdater::class.java), unusedProxy(ForegroundUpdater::class.java),
    )
}

private fun <T> unusedProxy(type: Class<T>): T =
    type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null })!!

/** Detaches StationRepository's private lateinit database to simulate "not initialised"; returns a restore fn. */
internal fun detachRepositoryDatabase(): () -> Unit {
    val field = StationRepository::class.java.getDeclaredField("database").apply { isAccessible = true }
    val original = field.get(StationRepository)
    field.set(StationRepository, null)
    return { field.set(StationRepository, original) }
}
