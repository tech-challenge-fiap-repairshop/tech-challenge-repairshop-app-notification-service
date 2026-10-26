package br.com.fiap.repairshop.notification

import br.com.fiap.repairshop.notification.infrastructure.messaging.NotificationSqsMessenger
import br.com.fiap.repairshop.notification.infrastructure.messaging.OrderApprovalInboundEvent
import br.com.fiap.repairshop.notification.infrastructure.messaging.PaymentReceiptInboundEvent
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mail.MailSendException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.*
import java.util.UUID
import java.util.function.Consumer

class NotificationSqsMessengerTest {

    private val sqsClient: SqsClient = mockk(relaxed = true)
    private val mailSender: JavaMailSender = mockk(relaxed = true)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private lateinit var messenger: NotificationSqsMessenger

    private val approveQueue = "approve-notification-queue"
    private val callbackQueue = "callback-approve-queue"
    private val paymentQueue = "payment-notification-queue"

    @BeforeEach
    fun setUp() {
        clearAllMocks()

        every { sqsClient.getQueueUrl(any<Consumer<GetQueueUrlRequest.Builder>>()) } answers {
            val consumer = firstArg<Consumer<GetQueueUrlRequest.Builder>>()
            val builder = GetQueueUrlRequest.builder()
            consumer.accept(builder)
            val req = builder.build()
            GetQueueUrlResponse.builder().queueUrl("https://sqs.us-east-1.amazonaws.com/123/${req.queueName()}").build()
        }

        messenger = NotificationSqsMessenger(
            sqsClient = sqsClient,
            mailSender = mailSender,
            objectMapper = objectMapper,
            approveNotificationQueue = approveQueue,
            callbackApproveQueue = callbackQueue,
            paymentNotificationQueue = paymentQueue
        )
    }

    @Test
    fun `should poll approval notifications and send email and delete message`() {
        val orderId = UUID.randomUUID()
        val customerId = UUID.randomUUID()
        val event = OrderApprovalInboundEvent(
            orderId = orderId,
            customerId = customerId,
            vehicleId = UUID.randomUUID(),
            totalPrice = 1250.0,
            description = "Troca de óleo e pastilhas"
        )
        val json = objectMapper.writeValueAsString(event)

        val message = Message.builder()
            .body(json)
            .receiptHandle("handle-approve-123")
            .build()

        val response = ReceiveMessageResponse.builder()
            .messages(listOf(message))
            .build()

        every { sqsClient.receiveMessage(any<ReceiveMessageRequest>()) } returns response
        every { sqsClient.deleteMessage(any<DeleteMessageRequest>()) } returns DeleteMessageResponse.builder().build()

        val mailSlot = slot<SimpleMailMessage>()
        every { mailSender.send(capture(mailSlot)) } just Runs

        messenger.pollApprovalNotifications()

        assertEquals("PENDING_APPROVAL", NotificationSqsMessenger.notificationHistory[orderId])
        verify(exactly = 1) { mailSender.send(any<SimpleMailMessage>()) }
        assertTrue(mailSlot.captured.to?.first()?.contains(customerId.toString()) == true)
        assertTrue(mailSlot.captured.text?.contains("1250") == true)

        val deleteSlot = slot<DeleteMessageRequest>()
        verify(exactly = 1) { sqsClient.deleteMessage(capture(deleteSlot)) }
        assertEquals("handle-approve-123", deleteSlot.captured.receiptHandle())
    }

    @Test
    fun `should handle email send error gracefully during poll approval notifications`() {
        val orderId = UUID.randomUUID()
        val event = OrderApprovalInboundEvent(
            orderId = orderId,
            customerId = UUID.randomUUID(),
            vehicleId = UUID.randomUUID(),
            totalPrice = 500.0
        )
        val json = objectMapper.writeValueAsString(event)
        val message = Message.builder().body(json).receiptHandle("handle-err").build()
        val response = ReceiveMessageResponse.builder().messages(listOf(message)).build()

        every { sqsClient.receiveMessage(any<ReceiveMessageRequest>()) } returns response
        every { mailSender.send(any<SimpleMailMessage>()) } throws MailSendException("SMTP connection timeout")
        every { sqsClient.deleteMessage(any<DeleteMessageRequest>()) } returns DeleteMessageResponse.builder().build()

        assertDoesNotThrow {
            messenger.pollApprovalNotifications()
        }

        verify(exactly = 1) { sqsClient.deleteMessage(any<DeleteMessageRequest>()) }
    }

    @Test
    fun `should handle SQS receive error gracefully during poll approval notifications`() {
        every { sqsClient.receiveMessage(any<ReceiveMessageRequest>()) } throws SqsException.builder().message("SQS error").build()

        assertDoesNotThrow {
            messenger.pollApprovalNotifications()
        }

        verify(exactly = 0) { mailSender.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `should poll payment notifications and send receipt email and delete message`() {
        val orderId = UUID.randomUUID()
        val customerId = UUID.randomUUID()
        val event = PaymentReceiptInboundEvent(
            orderId = orderId,
            customerId = customerId,
            paymentId = "pay-rec-456",
            amount = 890.50,
            status = "APPROVED"
        )
        val json = objectMapper.writeValueAsString(event)

        val message = Message.builder()
            .body(json)
            .receiptHandle("handle-pay-456")
            .build()

        val response = ReceiveMessageResponse.builder()
            .messages(listOf(message))
            .build()

        every { sqsClient.receiveMessage(any<ReceiveMessageRequest>()) } returns response
        every { sqsClient.deleteMessage(any<DeleteMessageRequest>()) } returns DeleteMessageResponse.builder().build()

        val mailSlot = slot<SimpleMailMessage>()
        every { mailSender.send(capture(mailSlot)) } just Runs

        messenger.pollPaymentNotifications()

        assertEquals("PAYMENT_CONFIRMED", NotificationSqsMessenger.notificationHistory[orderId])
        verify(exactly = 1) { mailSender.send(any<SimpleMailMessage>()) }
        assertTrue(mailSlot.captured.text?.contains("pay-rec-456") == true)
        assertTrue(mailSlot.captured.text?.contains("890") == true)

        val deleteSlot = slot<DeleteMessageRequest>()
        verify(exactly = 1) { sqsClient.deleteMessage(capture(deleteSlot)) }
        assertEquals("handle-pay-456", deleteSlot.captured.receiptHandle())
    }

    @Test
    fun `should handle email send error gracefully during poll payment notifications`() {
        val orderId = UUID.randomUUID()
        val event = PaymentReceiptInboundEvent(
            orderId = orderId,
            customerId = UUID.randomUUID(),
            paymentId = "pay-rec-err",
            amount = 100.0,
            status = "APPROVED"
        )
        val json = objectMapper.writeValueAsString(event)
        val message = Message.builder().body(json).receiptHandle("handle-pay-err").build()
        val response = ReceiveMessageResponse.builder().messages(listOf(message)).build()

        every { sqsClient.receiveMessage(any<ReceiveMessageRequest>()) } returns response
        every { mailSender.send(any<SimpleMailMessage>()) } throws MailSendException("SMTP error")
        every { sqsClient.deleteMessage(any<DeleteMessageRequest>()) } returns DeleteMessageResponse.builder().build()

        assertDoesNotThrow {
            messenger.pollPaymentNotifications()
        }

        verify(exactly = 1) { sqsClient.deleteMessage(any<DeleteMessageRequest>()) }
    }

    @Test
    fun `should handle SQS receive error gracefully during poll payment notifications`() {
        every { sqsClient.receiveMessage(any<ReceiveMessageRequest>()) } throws SqsException.builder().message("Queue down").build()

        assertDoesNotThrow {
            messenger.pollPaymentNotifications()
        }

        verify(exactly = 0) { mailSender.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `should send approval decision successfully with traceparent`() {
        val orderId = UUID.randomUUID()
        val slot = slot<SendMessageRequest>()
        every { sqsClient.sendMessage(capture(slot)) } returns SendMessageResponse.builder().build()

        messenger.sendApprovalDecision(orderId, true, "Cliente aprovou por telefone")

        verify(exactly = 1) { sqsClient.sendMessage(any<SendMessageRequest>()) }
        assertTrue(slot.captured.queueUrl().contains(callbackQueue))
        assertTrue(slot.captured.messageBody().contains("true"))
        assertTrue(slot.captured.messageBody().contains("Cliente aprovou por telefone"))
        assertTrue(slot.captured.messageAttributes().containsKey("traceparent"))
        assertEquals("APPROVED_BY_CLIENT", NotificationSqsMessenger.notificationHistory[orderId])
    }

    @Test
    fun `should send rejection decision successfully`() {
        val orderId = UUID.randomUUID()
        val slot = slot<SendMessageRequest>()
        every { sqsClient.sendMessage(capture(slot)) } returns SendMessageResponse.builder().build()

        messenger.sendApprovalDecision(orderId, false, "Orçamento muito caro")

        verify(exactly = 1) { sqsClient.sendMessage(any<SendMessageRequest>()) }
        assertTrue(slot.captured.messageBody().contains("false"))
        assertTrue(slot.captured.messageBody().contains("Orçamento muito caro"))
        assertEquals("REJECTED_BY_CLIENT", NotificationSqsMessenger.notificationHistory[orderId])
    }
}
