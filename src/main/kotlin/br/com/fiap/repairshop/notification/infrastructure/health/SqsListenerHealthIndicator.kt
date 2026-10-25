package br.com.fiap.repairshop.notification.infrastructure.health

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * HealthIndicator especializado para monitorar listeners SQS do Notification Service (PF-11).
 * Detecta Silent Zombie Pods e orienta a livenessProbe do Kubernetes para reiniciar o Pod em caso de travamento.
 */
@Component("sqsListener")
class SqsListenerHealthIndicator(
    @Value("\${notification.sqs.health-check.enabled:true}")
    private val enabled: Boolean,
    @Value("\${notification.sqs.health-check.max-idle-seconds:120}")
    private val defaultMaxIdleSeconds: Long
) : HealthIndicator {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val queueHeartbeats = ConcurrentHashMap<String, Instant>()
        private val queueMaxIdleConfig = ConcurrentHashMap<String, Long>()

        @JvmStatic
        fun recordHeartbeat(queueName: String) {
            queueHeartbeats[queueName] = Instant.now()
        }

        @JvmStatic
        fun registerMonitoredQueue(queueName: String, maxIdleSeconds: Long? = null) {
            queueHeartbeats.putIfAbsent(queueName, Instant.now())
            if (maxIdleSeconds != null) {
                queueMaxIdleConfig[queueName] = maxIdleSeconds
            }
        }
    }

    override fun health(): Health {
        if (!enabled) {
            return Health.up().withDetail("status", "DISABLED").build()
        }

        if (queueHeartbeats.isEmpty()) {
            return Health.up()
                .withDetail("status", "STANDBY")
                .withDetail("message", "Nenhum listener SQS monitorado registrado ainda")
                .build()
        }

        val now = Instant.now()
        val builder = Health.up()
        var hasFailures = false
        val failedQueues = mutableListOf<String>()

        queueHeartbeats.forEach { (queueName, lastHeartbeat) ->
            val maxIdle = queueMaxIdleConfig[queueName] ?: defaultMaxIdleSeconds
            val elapsedSeconds = Duration.between(lastHeartbeat, now).seconds

            builder.withDetail("queue.$queueName.elapsed_seconds", elapsedSeconds)
            if (elapsedSeconds > maxIdle) {
                hasFailures = true
                failedQueues.add("$queueName (${elapsedSeconds}s > ${maxIdle}s)")
            }
        }

        return if (hasFailures) {
            logger.error("Notification Service - SQS Listener Health FALHOU: Queues travadas: {}", failedQueues)
            Health.down()
                .withDetail("failed_queues", failedQueues)
                .withDetail("reason", "Heartbeat de fila SQS expirado (Possível Silent Zombie Pod)")
                .build()
        } else {
            builder.withDetail("monitored_queues_count", queueHeartbeats.size).build()
        }
    }
}
