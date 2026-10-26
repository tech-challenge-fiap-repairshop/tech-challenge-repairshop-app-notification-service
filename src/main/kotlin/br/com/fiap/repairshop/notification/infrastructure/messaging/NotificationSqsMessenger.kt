package br.com.fiap.repairshop.notification.infrastructure.messaging

import br.com.fiap.repairshop.notification.infrastructure.health.SqsListenerHealthIndicator
import com.fasterxml.jackson.databind.ObjectMapper
import io.opentelemetry.api.trace.Span
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class OrderApprovalInboundEvent(
    val orderId: UUID,
    val customerId: UUID,
    val vehicleId: UUID,
    val totalPrice: Double,
    val description: String? = null
)

data class CallbackApprovalMessage(
    val orderId: UUID,
    val approved: Boolean,
    val reason: String? = null
)

data class PaymentReceiptInboundEvent(
    val orderId: UUID,
    val customerId: UUID,
    val paymentId: String,
    val amount: Double,
    val status: String
)

@Component
class NotificationSqsMessenger(
    private val sqsClient: SqsClient,
    private val mailSender: JavaMailSender,
    private val objectMapper: ObjectMapper,
    @Value("\${sqs.queue.approve-notification:approve-notification-queue-dev}")
    private val approveNotificationQueue: String,
    @Value("\${sqs.queue.callback-approve:callback-approve-notification-queue-dev}")
    private val callbackApproveQueue: String,
    @Value("\${sqs.queue.payment-notification:payment-notification-queue-dev}")
    private val paymentNotificationQueue: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Armazena em memória as notificações recentes para consulta
    companion object {
        val notificationHistory = ConcurrentHashMap<UUID, String>()
    }

    init {
        SqsListenerHealthIndicator.registerMonitoredQueue(approveNotificationQueue, 120)
        SqsListenerHealthIndicator.registerMonitoredQueue(paymentNotificationQueue, 120)
    }

    private fun getQueueUrl(queueName: String): String {
        return runCatching {
            sqsClient.getQueueUrl { it.queueName(queueName) }.queueUrl()
        }.getOrElse { queueName }
    }

    /**
     * Consumo da fila de ordens aguardando aprovação do cliente
     */
    @Scheduled(fixedDelay = 5000)
    fun pollApprovalNotifications() {
        val queueUrl = getQueueUrl(approveNotificationQueue)
        runCatching {
            val response = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(2)
                    .messageAttributeNames("All")
                    .build()
            )

            SqsListenerHealthIndicator.recordHeartbeat(approveNotificationQueue)

            for (msg in response.messages()) {
                val event = objectMapper.readValue(msg.body(), OrderApprovalInboundEvent::class.java)
                notificationHistory[event.orderId] = "PENDING_APPROVAL"

                sendApprovalEmail(event)

                sqsClient.deleteMessage(
                    DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(msg.receiptHandle())
                        .build()
                )
            }
        }.onFailure { ex ->
            logger.warn("Erro ao ler fila {}: {}", approveNotificationQueue, ex.message)
        }
    }

    /**
     * Consumo da fila de confirmação de pagamento para envio de recibo e liberação
     */
    @Scheduled(fixedDelay = 5000)
    fun pollPaymentNotifications() {
        val queueUrl = getQueueUrl(paymentNotificationQueue)
        runCatching {
            val response = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(2)
                    .messageAttributeNames("All")
                    .build()
            )

            SqsListenerHealthIndicator.recordHeartbeat(paymentNotificationQueue)

            for (msg in response.messages()) {
                val event = objectMapper.readValue(msg.body(), PaymentReceiptInboundEvent::class.java)
                notificationHistory[event.orderId] = "PAYMENT_CONFIRMED"

                sendPaymentReceiptEmail(event)

                sqsClient.deleteMessage(
                    DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(msg.receiptHandle())
                        .build()
                )
            }
        }.onFailure { ex ->
            logger.warn("Erro ao ler fila {}: {}", paymentNotificationQueue, ex.message)
        }
    }

    /**
     * Publica a decisão do cliente na fila de callback para a OS (PF-10 W3C Trace)
     */
    fun sendApprovalDecision(orderId: UUID, approved: Boolean, reason: String?) {
        val queueUrl = getQueueUrl(callbackApproveQueue)
        val event = CallbackApprovalMessage(orderId, approved, reason)
        val body = objectMapper.writeValueAsString(event)

        val currentSpan = Span.current()
        val traceparent = "00-${currentSpan.spanContext.traceId}-${currentSpan.spanContext.spanId}-01"

        sqsClient.sendMessage(
            SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .messageAttributes(mapOf(
                    "traceparent" to MessageAttributeValue.builder().dataType("String").stringValue(traceparent).build()
                ))
                .build()
        )
        notificationHistory[orderId] = if (approved) "APPROVED_BY_CLIENT" else "REJECTED_BY_CLIENT"
        logger.info("Resposta de aprovação enviada para a OS via {} (OS: {}, Aprovado: {})", callbackApproveQueue, orderId, approved)
    }

    private fun sendApprovalEmail(event: OrderApprovalInboundEvent) {
        runCatching {
            val message = SimpleMailMessage().apply {
                setTo("cliente-${event.customerId}@repairshop.com.br")
                setSubject("Oficina Mecânica - Aprovação de Orçamento da OS ${event.orderId}")
                setText("""
                    Olá!
                    O diagnóstico do seu veículo foi concluído.
                    Valor total do orçamento: R$ ${"%.2f".format(event.totalPrice)}
                    Descrição dos serviços: ${event.description ?: "Revisão geral"}

                    Para aprovar ou rejeitar, acesse o link de aprovação no aplicativo ou responda por este canal.
                """.trimIndent())
            }
            mailSender.send(message)
            logger.info("E-mail de aprovação disparado via Mailpit para OS {}", event.orderId)
        }.onFailure { ex ->
            logger.warn("Falha ao enviar e-mail via Mailpit: {}", ex.message)
        }
    }

    private fun sendPaymentReceiptEmail(event: PaymentReceiptInboundEvent) {
        runCatching {
            val message = SimpleMailMessage().apply {
                setTo("cliente-${event.customerId}@repairshop.com.br")
                setSubject("Oficina Mecânica - Pagamento Confirmado e Veículo Liberado!")
                setText("""
                    Olá!
                    O pagamento referente à sua Ordem de Serviço ${event.orderId} foi confirmado com sucesso.
                    Comprovante: ${event.paymentId}
                    Valor pago: R$ ${"%.2f".format(event.amount)}

                    Seu veículo está pronto e higienizado para retirada na nossa oficina!
                """.trimIndent())
            }
            mailSender.send(message)
            logger.info("E-mail de recibo disparado via Mailpit para OS {}", event.orderId)
        }.onFailure { ex ->
            logger.warn("Falha ao enviar e-mail de recibo via Mailpit: {}", ex.message)
        }
    }
}
