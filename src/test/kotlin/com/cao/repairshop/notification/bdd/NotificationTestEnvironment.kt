package com.cao.repairshop.notification.bdd

import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest

object NotificationTestEnvironment {
    const val approvalQueue = "notification-bdd-approval"
    const val callbackQueue = "notification-bdd-callback"
    const val paymentQueue = "notification-bdd-payment"

    private val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
        .withServices(LocalStackContainer.Service.SQS)

    val endpoint: String
        get() = localstack.endpoint.toString()

    @Synchronized
    fun start() {
        if (!localstack.isRunning) {
            System.setProperty("aws.accessKeyId", "test")
            System.setProperty("aws.secretAccessKey", "test")
            localstack.start()
            createQueues()
        }
    }

    private fun createQueues() {
        SqsClient.builder()
            .endpointOverride(localstack.endpoint)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
            )
            .region(Region.of("us-east-1"))
            .build()
            .use { client ->
                listOf(approvalQueue, callbackQueue, paymentQueue).forEach { queueName ->
                    client.createQueue(CreateQueueRequest.builder().queueName(queueName).build())
                }
            }
    }
}
