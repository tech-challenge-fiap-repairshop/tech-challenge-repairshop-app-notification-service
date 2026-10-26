package br.com.fiap.repairshop.notification

import br.com.fiap.repairshop.notification.infrastructure.health.SqsListenerHealthIndicator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import java.lang.reflect.Field
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class SqsListenerHealthIndicatorTest {

    @BeforeEach
    fun clearState() {
        val heartbeatsField: Field = SqsListenerHealthIndicator::class.java.getDeclaredField("queueHeartbeats")
        heartbeatsField.isAccessible = true
        val heartbeats = heartbeatsField.get(null) as ConcurrentHashMap<*, *>
        heartbeats.clear()

        val maxIdleField: Field = SqsListenerHealthIndicator::class.java.getDeclaredField("queueMaxIdleConfig")
        maxIdleField.isAccessible = true
        val maxIdle = maxIdleField.get(null) as ConcurrentHashMap<*, *>
        maxIdle.clear()
    }

    @Test
    fun `should return DISABLED when indicator is not enabled`() {
        val indicator = SqsListenerHealthIndicator(enabled = false, defaultMaxIdleSeconds = 120)

        val health = indicator.health()

        assertEquals(Status.UP, health.status)
        assertEquals("DISABLED", health.details["status"])
    }

    @Test
    fun `should return STANDBY when no queues are monitored yet`() {
        val indicator = SqsListenerHealthIndicator(enabled = true, defaultMaxIdleSeconds = 120)

        val health = indicator.health()

        assertEquals(Status.UP, health.status)
        assertEquals("STANDBY", health.details["status"])
        assertTrue(health.details.containsKey("message"))
    }

    @Test
    fun `should return UP when monitored queues have fresh heartbeats`() {
        SqsListenerHealthIndicator.registerMonitoredQueue("test-active-queue", 60)
        SqsListenerHealthIndicator.recordHeartbeat("test-active-queue")

        val indicator = SqsListenerHealthIndicator(enabled = true, defaultMaxIdleSeconds = 120)

        val health = indicator.health()

        assertEquals(Status.UP, health.status)
        assertEquals(1, health.details["monitored_queues_count"])
    }

    @Test
    fun `should return DOWN when monitored queue has expired heartbeat`() {
        val queueName = "test-stalled-queue"
        SqsListenerHealthIndicator.registerMonitoredQueue(queueName, 1)

        val heartbeatsField: Field = SqsListenerHealthIndicator::class.java.getDeclaredField("queueHeartbeats")
        heartbeatsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val heartbeats = heartbeatsField.get(null) as ConcurrentHashMap<String, Instant>
        heartbeats[queueName] = Instant.now().minusSeconds(10)

        val indicator = SqsListenerHealthIndicator(enabled = true, defaultMaxIdleSeconds = 1)

        val health = indicator.health()

        assertEquals(Status.DOWN, health.status)
        assertTrue(health.details.containsKey("failed_queues"))
        assertEquals("Heartbeat de fila SQS expirado (Possível Silent Zombie Pod)", health.details["reason"])
    }
}
