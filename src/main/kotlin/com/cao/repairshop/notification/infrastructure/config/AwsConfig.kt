package com.cao.repairshop.notification.infrastructure.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import java.net.URI

/**
 * Configuração enxuta dos clientes AWS SDK v2 para o Notification Service:
 * - No EKS (AWS Academy): resolve automaticamente credenciais STS temporárias e auto-rotacionadas da LabRole via IMDSv2.
 * - Local / Docker Compose: resolve variáveis nativas do SDK (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_SESSION_TOKEN) ou ~/.aws/credentials.
 * - Testes integrados: resolve System Properties (aws.accessKeyId / aws.secretAccessKey) + endpointOverride.
 */
@Configuration(proxyBeanMethods = false)
class AwsConfig(
    @Value("\${aws.region:\${AWS_REGION:us-east-1}}")
    private val region: String,
    @Value("\${aws.endpoint-url:\${AWS_ENDPOINT_URL:#{null}}}")
    private val endpointUrl: String? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private fun credentialsProvider(): DefaultCredentialsProvider =
        DefaultCredentialsProvider.builder().build()

    @Bean
    fun sqsClient(): SqsClient {
        val builder = SqsClient.builder()
            .region(Region.of(region))
            .credentialsProvider(credentialsProvider())

        if (!endpointUrl.isNullOrBlank()) {
            logger.info("Notification Service - Conectando SQS em: {}", endpointUrl)
            builder.endpointOverride(URI.create(endpointUrl))
        }

        return builder.build()
    }
}
