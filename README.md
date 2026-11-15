# ✉️🔔 Repair Shop — Notification Service (`app-notification-service`)

[![CI/CD Pipeline](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-app-notification-service/actions/workflows/ci.yml/badge.svg)](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-app-notification-service/actions/workflows/ci.yml)
[![Java 24](https://img.shields.io/badge/Java-24-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Kotlin 2.2](https://img.shields.io/badge/Kotlin-2.2.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.0.6-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)

Microsserviço responsável pela **comunicação assíncrona com clientes e retorno de aprovações de orçamento** na plataforma **Repair Shop (Tech Challenge FIAP — 15SOAT)**. Consome eventos das filas **Amazon SQS**, realiza o envio de e-mails transacionais (orçamento e comprovante de pagamento), mantém histórico em memória das notificações processadas e publica o retorno de aprovação/rejeição do cliente na fila de callback propagando o contexto W3C (`traceparent`).

---

## 📋 Índice

- [Responsabilidades, Endpoints e Filas SQS](#responsabilidades-endpoints-e-filas-sqs)
- [Arquitetura Atual — Motivação e Benefícios dos Componentes](#arquitetura-atual--motivação-e-benefícios-dos-componentes)
- [Por que DynamoDB em vez de Banco Relacional nos Contextos de OS e Pagamentos?](#por-que-dynamodb-em-vez-de-banco-relacional-nos-contextos-de-os-e-pagamentos)
- [Como Executar Localmente](#como-executar-localmente)
- [Como Executar em Produção (Esteira CI/CD GitHub Actions)](#como-executar-em-produção-esteira-cicd-github-actions)
- [Documentação Técnica e Collection do Postman](#documentação-técnica-e-collection-do-postman)

---

## Responsabilidades, Endpoints e Filas SQS

O serviço executa internamente na porta **`8080`** (exposto na porta **`8083`** no Docker Compose local) com endpoints definidos em [`NotificationApprovalController`](src/main/kotlin/com/cao/repairshop/notification/infrastructure/web/NotificationApprovalController.kt):

| Método | Rota | Descrição |
| :--- | :--- | :--- |
| `POST` | `/notifications/approval/{orderId}` | Endpoint público onde o cliente aprova ou rejeita o orçamento da Ordem de Serviço (`approved: Boolean`, `reason: String?`), publicando a decisão na fila SQS de callback com propagação `traceparent`. |
| `GET` | `/notifications/history` | Lista o histórico recente mantido em memória (`ConcurrentHashMap` em `NotificationSqsMessenger`) com o status das notificações processadas (`PENDING_APPROVAL`, `PAYMENT_CONFIRMED`, `APPROVED_BY_CLIENT`, `REJECTED_BY_CLIENT`). |

### Consumo e Produção de Filas (`NotificationSqsMessenger`)
- **Polling automático (`@Scheduled(fixedDelay = 5000)`)**:
  - **`approve-notification-queue-dev`**: Consome solicitações de aprovação de orçamento enviadas pelo `service-order-execution-service` e dispara e-mail via `JavaMailSender` (Mailpit em desenvolvimento/EKS).
  - **`payment-notification-queue-dev`**: Consome notificações de pagamento enviadas pelo `payment-service` e dispara e-mail com comprovante/liberação do veículo.
- **Publicação de callback**:
  - **`callback-approve-notification-queue-dev`**: Publica a decisão do cliente (`orderId`, `approved`, `reason`) injetando o header W3C `traceparent` nos `MessageAttributes`.
- **Health Probes (Actuator)**: `/actuator/health/liveness` (`livenessState,sqsListener` monitorado pelo `SqsListenerHealthIndicator` com tolerância máxima de inatividade de `120s` por fila) e `/actuator/health/readiness`.

---

## Arquitetura Atual — Motivação e Benefícios dos Componentes

| Pilar / Tecnologia | Papel no Ecossistema | Motivação Técnica e Pontos Positivos |
| :--- | :--- | :--- |
| **RDS (PostgreSQL 16)** | Persistência relacional cadastral no [`app-register-service`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-app-register-service) ([`infra-db-rds`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-infra-db-rds)) | Mantido exclusivo para cadastros com integridade referencial forte (`tb_customer`, `tb_vehicle`, `tb_user`), isolando completamente a lógica de envio de notificações e callbacks de banco SQL. |
| **DynamoDB (NoSQL)** | Persistência dos irmãos `service-order` e `payment` ([`infra-db-dynamo`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-infra-db-dynamo)) | Garante leitura/escrita de alta concorrência orientada a agregados (`ORDER#<id>`, `BILLING#<id>`) e escalabilidade automática `PAY_PER_REQUEST`, sem acoplamento de pool JDBC ao fluxo de mensagens. |
| **ElastiCache (Redis 7.0)** | Cache em memória provisionado via [`infra-redis`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-infra-redis) | Prepara a camada de autenticação/cadastro (`register-service`) para cache sub-milissegundo de sessão e credenciais, mantendo o restante da malha desacoplado. |
| **Apps (EKS + Lambda Auth)** | Container no **EKS (`1.31`)** ([`infra-eks`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-infra-eks)) | Executa como microsserviço *stateless* leve (Java 24 / Kotlin) com HPA baseado em carga de mensagens/HTTP, convivendo com o [`lambda-auth`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-lambda-auth) serverless na borda de login. |
| **Amazon SQS (Mensageria + DLQ)** | **Meio de transporte principal deste serviço** ([`infra-sqs`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-infra-sqs)) | Permite que lentidões no servidor SMTP ou picos de abertura/faturamento de OS sejam amortecidos pelas filas sem travar o cliente na API REST. Mensagens com erro persistente após retentativas são redirecionadas automaticamente para DLQs com retenção de 14 dias. |
| **API Gateway (HTTP API v2)** | Ponto de entrada único ([`infra-apigateway`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-infra-apigateway)) | Expõe `ANY /notifications` e `ANY /notifications/{proxy+}` via `HTTP_PROXY` para o NLB interno deste microsserviço no EKS, com CORS configurado para headers OpenTelemetry (`traceparent`, `tracestate`). |
| **Observabilidade (Grafana)** | Dashboards `notification-service-observability` e `notification-service-logs-traces` | Coleta de métricas HTTP/JVM via OpenTelemetry -> Prometheus, logs correlacionados via Loki e rastreamento distribuído no Jaeger com continuidade via atributo `traceparent` entre SQS e chamadas HTTP. |

---

## Por que DynamoDB em vez de Banco Relacional nos Contextos de OS e Pagamentos?

Embora o `notification-service` atue de forma *stateless* sobre mensageria SQS e histórico recente em memória, o ecossistema adota **DynamoDB** nos contextos produtores/consumidores adjacentes (`service-order-execution-service` e `payment-service`) pelos seguintes motivos técnicos:

1. **Zero contenção de conexões sob mensageria paralela e HPA**: Múltiplas réplicas de microsserviços consumindo filas SQS e webhooks em paralelo esgotariam rapidamente conexões TCP em um banco relacional centralizado (como RDS `db.t3.micro`). O DynamoDB atende chamadas via HTTPS de forma *stateless* e escala sob demanda (`PAY_PER_REQUEST`).
2. **Agregados polimórficos e evolução rápida de schema**: Ordens de Serviço (`ORDER#<id>`) e Faturamento (`BILLING#<id>`) concentram itens heterogêneos (status, histórico, tarefas extras, pagamento, fatura) em **Single-Table Design** com atomicidade garantida via `TransactWriteItems` e sem `JOIN`s ou *locks* de `ALTER TABLE`.
3. **Consultas assíncronas via GSIs dedicados**: Índices secundários (`pending-dispatch-index`, `dispatch-notification-index`) alimentam os disparos assíncronos para as filas de notificação e pagamento sem necessidade de `SCAN` em toda a base.

---

## Como Executar Localmente

> **Nota sobre build Maven**: Este repositório **não inclui Maven Wrapper** (`mvnw`). Utilize uma instalação local do **Maven 3.9+** (`mvn`) com **JDK 24**.

### Pré-requisitos
- **Docker** e **Docker Compose**
- **JDK 24** e **Apache Maven 3.9+** (`mvn`)

### Passo a Passo com Docker Compose + LocalStack + Mailpit (Recomendado)

O arquivo [`docker-compose.yml`](docker-compose.yml) sobe o **LocalStack** (`SERVICES=sqs`, porta `4566`), o **Mailpit** (`:1025` SMTP e `:8025` Web UI) e o container `notification-service` mapeado na porta **`8083`** (`8083:8080`):

```bash
# Subir LocalStack (SQS), Mailpit e o Notification Service
docker compose up -d --build

# Acompanhar os logs
docker compose logs -f notification-service
```

- **Mailpit Web UI (visualização dos e-mails enviados)**: `http://localhost:8025`
- **API Notification Service**: `http://localhost:8083` (`GET /notifications/history`, `POST /notifications/approval/{orderId}`)
- **Healthcheck**: `http://localhost:8083/actuator/health`

Principais variáveis ([`application.properties`](src/main/resources/application.properties)):
- `SQS_QUEUE_APPROVE_NOTIFICATION` (`approve-notification-queue-dev`)
- `SQS_QUEUE_PAYMENT_NOTIFICATION` (`payment-notification-queue-dev`)
- `SQS_QUEUE_CALLBACK_APPROVE` (`callback-approve-notification-queue-dev`)
- `SPRING_MAIL_HOST` (`localhost`), `SPRING_MAIL_PORT` (`1025`)

Para rodar testes e gerar cobertura via Maven:
```bash
mvn clean verify
```

Para derrubar os containers:
```bash
docker compose down -v
```

---

## Como Executar em Produção (Esteira CI/CD GitHub Actions)

O pipeline está em [`.github/workflows/ci.yml`](.github/workflows/ci.yml), disparado em `main`, `homolog`, `develop` e `workflow_dispatch`.

### Etapas da Pipeline (`ci.yml`)
1. **`build`**: Compila com JDK 24 (`mvn clean package -DskipTests`) e executa smoke test local validando inicialização e `/actuator/health`.
2. **`test`**: Executa `mvn clean verify` com publicação de relatórios **JaCoCo** e **Cucumber**.
3. **`quality`**: Executa scan **Trivy FS** no `pom.xml` e análise estática no **SonarCloud**.
4. **`docker-build-push`**: Constrói a imagem para o **Amazon ECR** (`notification-service-{env}`), aplica scan de segurança de imagem com **Trivy** e realiza smoke test no container.
5. **`deploy`**: Atualiza `kubeconfig` no EKS (`repairshop-eks-{env}`), aplica os manifestos em [`k8s/`](k8s/) (`namespace.yaml`, `secret.yaml`, `deployment.yaml`, `service.yaml`, `hpa.yaml`) e valida o `rollout status`.

---

## Documentação Técnica e Collection do Postman

- **Postman Collections & Environments (Organização)**:
  - Collection Principal: [`Repair Shop (Tech Challenge FIAP)`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-app-service-order-execution-service/blob/main/docs/postman/Repair%20Shop%20%28Tech%20Challenge%20FIAP%29.postman_collection.json)
  - Collection Completa: [`Tech Challenge FIAP - Completo`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-app-service-order-execution-service/blob/main/docs/postman/Tech_Challenge_Fiap_-_Completo.postman_collection.json)
  - Environment Local: [`Local.postman_environment.json`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-app-service-order-execution-service/blob/main/docs/postman/Local.postman_environment.json)
  - Guia do Postman: [`docs/postman/README.md`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-app-service-order-execution-service/blob/main/docs/postman/README.md)
- **Dashboards Grafana deste Serviço**:
  - Observabilidade Geral: [`repairshop-notification-service-observability.json`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-app-service-order-execution-service/blob/main/local/observability/grafana/dashboards/repairshop-notification-service-observability.json)
  - Logs e Traces: [`repairshop-notification-service-logs-traces.json`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-app-service-order-execution-service/blob/main/local/observability/grafana/dashboards/repairshop-notification-service-logs-traces.json)
- **ADRs, RFCs e Diagramas**:
  - [ADRs (`ADR-001` a `ADR-013`)](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-app-service-order-execution-service/blob/main/ADRs/README.md)
  - [RFCs (`RFC-001` a `RFC-014`)](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-app-service-order-execution-service/blob/main/RFCs/README.md)
  - [`Diagrama de Infraestrutura Cloud`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-app-service-order-execution-service/blob/main/docs/delivery/infrastructure/repairshop-diagrama-infra-cloud.svg)
- **Repositórios relacionados**: [`infra-sqs`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-infra-sqs) | [`app-service-order-execution-service`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-app-service-order-execution-service) | [`app-payment-service`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-app-payment-service) | [`infra-apigateway`](https://github.com/tech-challenge-fiap-repairshop/tech-challenge-repairshop-infra-apigateway)
