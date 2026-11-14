package com.cao.repairshop.notification.bdd

import com.fasterxml.jackson.databind.ObjectMapper
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.boot.test.web.server.LocalServerPort
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

class NotificationApprovalSteps(
    private val objectMapper: ObjectMapper,
    private val sqsClient: SqsClient,
    @LocalServerPort private val port: Int
) {
    private val httpClient = HttpClient.newHttpClient()
    private lateinit var response: HttpResponse<String>
    private var orderId: UUID? = null
    private var expectedApproved: Boolean? = null
    private var expectedReason: String? = null

    @Before
    fun clearCallbackQueue() {
        val queueUrl = callbackQueueUrl()
        val messages = sqsClient.receiveMessage(
            ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(0)
                .build()
        ).messages()
        messages.forEach { message ->
            sqsClient.deleteMessage(
                DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build()
            )
        }
    }

    @Given("the service order has a valid identifier")
    fun aValidServiceOrderIdExists() {
        orderId = UUID.randomUUID()
    }

    @When("the customer approves the budget without a reason")
    fun theCustomerApprovesWithoutReason() {
        submitDecision(approved = true, reason = null)
    }

    @When("the customer rejects the budget with reason {string}")
    fun theCustomerRejectsWithReason(reason: String) {
        submitDecision(approved = false, reason = reason)
    }

    @When("a decision is submitted for an invalid identifier")
    fun aDecisionIsSubmittedForAnInvalidId() {
        response = post("/notifications/approval/not-a-uuid", """{"approved":true}""")
    }

    @Then("the decision is confirmed and published to the callback queue")
    fun theDecisionIsConfirmedAndPublished() {
        val currentOrderId = requireNotNull(orderId)
        assertEquals(200, response.statusCode())

        val responseBody = objectMapper.readTree(response.body())
        assertEquals(currentOrderId.toString(), responseBody["orderId"].asText())
        assertEquals(expectedApproved, responseBody["approved"].asBoolean())
        assertEquals(
            if (expectedApproved == true) "ORCAMENTO_APROVADO" else "ORCAMENTO_REJEITADO",
            responseBody["status"].asText()
        )

        val queueUrl = callbackQueueUrl()
        val message = sqsClient.receiveMessage(
            ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(1)
                .messageAttributeNames("All")
                .build()
        ).messages().singleOrNull()
        assertTrue(message != null, "A decisÃ£o deveria estar na fila SQS de callback")

        val callback = objectMapper.readTree(message!!.body())
        assertEquals(currentOrderId.toString(), callback["orderId"].asText())
        assertEquals(expectedApproved, callback["approved"].asBoolean())
        if (expectedReason == null) {
            assertTrue(callback["reason"].isNull)
        } else {
            assertEquals(expectedReason, callback["reason"].asText())
        }
        assertTrue(message.messageAttributes().containsKey("traceparent"))

        sqsClient.deleteMessage(
            DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build()
        )

        val historyResponse = get("/notifications/history")
        assertEquals(200, historyResponse.statusCode())
        assertEquals(
            if (expectedApproved == true) "APPROVED_BY_CLIENT" else "REJECTED_BY_CLIENT",
            objectMapper.readTree(historyResponse.body())[currentOrderId.toString()].asText()
        )
    }

    @Then("the request is rejected and no decision is published")
    fun theRequestIsRejectedWithoutPublishing() {
        assertEquals(400, response.statusCode())
        assertFalse(
            sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(callbackQueueUrl())
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(0)
                    .build()
            ).messages().isNotEmpty()
        )
    }

    private fun submitDecision(approved: Boolean, reason: String?) {
        val currentOrderId = requireNotNull(orderId)
        expectedApproved = approved
        expectedReason = reason
        response = post(
            "/notifications/approval/$currentOrderId",
            objectMapper.writeValueAsString(mapOf("approved" to approved, "reason" to reason))
        )
    }

    private fun post(path: String, body: String): HttpResponse<String> =
        httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

    private fun get(path: String): HttpResponse<String> =
        httpClient.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )

    private fun callbackQueueUrl(): String =
        sqsClient.getQueueUrl { it.queueName(NotificationTestEnvironment.callbackQueue) }.queueUrl()
}
