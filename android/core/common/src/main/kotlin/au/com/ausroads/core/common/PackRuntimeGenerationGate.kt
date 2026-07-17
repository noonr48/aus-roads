package au.com.ausroads.core.common

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * One process-wide gate around map-pack component publication and consumption.
 *
 * Search queries and route calculations hold a read lease for the lifetime of
 * their component access. Pack activation holds the write lease while swapping
 * search, routing, and the map descriptor, so no consumer can observe a mixed
 * generation between those otherwise separate assignments.
 */
object PackRuntimeGenerationGate {
    private val lock = ReentrantReadWriteLock(true)

    fun <T> read(block: () -> T): T = lock.read(block)

    fun <T> write(block: () -> T): T = lock.write(block)
}
