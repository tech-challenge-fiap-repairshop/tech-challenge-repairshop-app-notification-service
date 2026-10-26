package br.com.fiap.repairshop.notification.infrastructure.web

import br.com.fiap.repairshop.notification.infrastructure.messaging.NotificationSqsMessenger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class ApprovalDecisionRequest(
    val approved: Boolean,
    val reason: String? = null
)

@RestController
@RequestMapping("/notifications")
@Tag(name = "Notifications", description = "Endpoints de Notificação e Aprovação de Orçamento")
class NotificationApprovalController(
    private val sqsMessenger: NotificationSqsMessenger
) {

    @PostMapping("/approval/{orderId}")
    @Operation(summary = "Endpoint público onde o cliente aprova ou rejeita o orçamento da OS")
    fun decideApproval(
        @PathVariable orderId: UUID,
        @RequestBody request: ApprovalDecisionRequest
    ): ResponseEntity<Map<String, Any>> {
        sqsMessenger.sendApprovalDecision(orderId, request.approved, request.reason)

        return ResponseEntity.ok(
            mapOf(
                "orderId" to orderId,
                "approved" to request.approved,
                "status" to if (request.approved) "ORCAMENTO_APROVADO" else "ORCAMENTO_REJEITADO",
                "message" to "Decisão enviada com sucesso para a oficina mecânica via mensageria SQS"
            )
        )
    }

    @GetMapping("/history")
    @Operation(summary = "Lista o histórico de notificações e status de ordens processadas")
    fun getHistory(): ResponseEntity<Map<UUID, String>> {
        return ResponseEntity.ok(NotificationSqsMessenger.notificationHistory)
    }
}
