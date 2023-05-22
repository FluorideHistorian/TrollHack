package dev.luna5ama.trollhack.util.threads

import dev.fastmc.common.ParallelUtils
import kotlinx.coroutines.*
import java.util.concurrent.ScheduledThreadPoolExecutor
import kotlin.math.max

/**
 * Single thread scope to use in Troll Hack
 */
@OptIn(DelicateCoroutinesApi::class)
val mainScope = CoroutineScope(newSingleThreadContext("Troll Hack Main"))

/**
 * Common scope with [Dispatchers.Default]
 */
object DefaultScope : CoroutineScope by CoroutineScope(Dispatchers.Default) {
    val context = Dispatchers.Default
}

private val concurrentContext = Dispatchers.Default.limitedParallelism(max(ParallelUtils.CPU_THREADS / 2, 1))

@OptIn(ExperimentalCoroutinesApi::class)
object ConcurrentScope : CoroutineScope by CoroutineScope(concurrentContext) {
    val context = concurrentContext
}

/**
 * Return true if the job is active, or false is not active or null
 */
inline val Job?.isActiveOrFalse get() = this?.isActive ?: false

suspend inline fun delay(timeMillis: Int) {
    delay(timeMillis.toLong())
}

private val backgroundPool = ScheduledThreadPoolExecutor(
    ParallelUtils.CPU_THREADS,
    CountingThreadFactory("Troll Hack Background") {
        isDaemon = true
        priority = 3
    }
)

private val backgroundContext = backgroundPool.asCoroutineDispatcher()

object BackgroundScope : CoroutineScope by CoroutineScope(backgroundContext) {
    val pool = backgroundPool
    val context = backgroundContext
}