package br.com.fiap.repairshop.notification

import br.com.fiap.repairshop.notification.infrastructure.messaging.NotificationSqsMessenger
import br.com.fiap.repairshop.notification.infrastructure.web.ApprovalDecisionRequest
import br.com.fiap.repairshop.notification.infrastructure.web.NotificationApprovalController
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.http.HttpStatus
import java.util.UUID

class NotificationApprovalControllerTest {

    private lateinit var messenger: NotificationSqsMessenger
    private lateinit var controller: NotificationApprovalController

    @BeforeEach
    fun setUp() {
        messenger = mock(NotificationSqsMessenger::class.java)
        controller = NotificationApprovalController(messenger)
    }

    @Test
    fun `should process approval decision when approved is true`() {
        val orderId = UUID.randomUUID()
        val request = ApprovalDecisionRequest(approved = true, reason = "Serviço aprovado")

        val res = controller.decideApproval(orderId, request)

        assertEquals(HttpStatus.OK, res.statusCode)
        assertNotNull(res.body)
        assertEquals(orderId, res.body?.get("orderId"))
        assertEquals(true, res.body?.get("approved"))
        assertEquals("ORCAMENTO_APROVADO", res.body?.get("status"))
        verify(messenger, times(1)).sendApprovalDecision(orderId, true, "Serviço aprovado")
    }

    @Test
    fun `should process rejection decision when approved is false`() {
        val orderId = UUID.randomUUID()
        val request = ApprovalDecisionRequest(approved = false, reason = "Preço elevado")

        val res = controller.decideApproval(orderId, request)

        assertEquals(HttpStatus.OK, res.statusCode)
        assertNotNull(res.body)
        assertEquals(orderId, res.body?.get("orderId"))
        assertEquals(false, res.body?.get("approved"))
        assertEquals("ORCAMENTO_REJEITADO", res.body?.get("status"))
        verify(messenger, times(1)).sendApprovalDecision(orderId, false, "Preço elevado")
    }

    @Test
    fun `should return history map`() {
        val res = controller.getHistory()

        assertEquals(HttpStatus.OK, res.statusCode)
        assertNotNull(res.body)
    }
}
