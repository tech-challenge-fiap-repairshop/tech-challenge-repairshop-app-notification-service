package com.cao.repairshop.notification.bdd

import io.cucumber.spring.CucumberContextConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@CucumberContextConfiguration
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "notification.sqs.health-check.enabled=false",
        "spring.mail.host=localhost",
        "spring.mail.port=1025"
    ]
)
class CucumberSpringConfiguration {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            NotificationTestEnvironment.start()
            registry.add("aws.endpoint-url") { NotificationTestEnvironment.endpoint }
            registry.add("aws.region") { "us-east-1" }
            registry.add("sqs.queue.approve-notification") { NotificationTestEnvironment.approvalQueue }
            registry.add("sqs.queue.callback-approve") { NotificationTestEnvironment.callbackQueue }
            registry.add("sqs.queue.payment-notification") { NotificationTestEnvironment.paymentQueue }
        }
    }
}
