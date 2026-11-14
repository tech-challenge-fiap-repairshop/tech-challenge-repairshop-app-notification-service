# Notification Service

Microsserviço de notificações e aprovação de orçamentos do Tech Challenge FIAP — **Fase 4**. Implementado em Kotlin com Spring Boot, integra filas AWS SQS e envio de e-mails via `JavaMailSender`.

## Funcionalidades

- Consome eventos de orçamento para aprovação e de confirmação de pagamento pelas filas SQS e envia, respectivamente, e-mails de aprovação e recibos.
- Recebe a decisão do cliente e publica o resultado na fila de callback da ordem de serviço, incluindo o atributo de rastreamento `traceparent`.
- Mantém em memória o histórico recente de status das ordens. Esse histórico não é persistido e é perdido quando a aplicação reinicia.

## API

| Método | Rota | Descrição |
|---|---|---|
| `POST` | `/notifications/approval/{orderId}` | Registra a decisão e a envia para a fila de callback. `orderId` deve ser um UUID. |
| `GET` | `/notifications/history` | Retorna o histórico de status mantido em memória pela instância. |

O corpo do `POST` recebe `approved` (booleano) e `reason` (texto opcional), por exemplo:

```json
{
  "approved": true,
  "reason": "Orçamento aprovado"
}
```

## Filas SQS

Os nomes usados no ambiente local são:

| Fila | Finalidade |
|---|---|
| `approve-notification-queue-dev` | Eventos de orçamento que originam e-mails de aprovação. |
| `payment-notification-queue-dev` | Eventos de pagamento que originam e-mails de recibo. |
| `callback-approve-notification-queue-dev` | Decisões do cliente publicadas para a ordem de serviço. |

## Executar localmente

Pré-requisitos: Docker com o comando `docker compose` disponível. O Compose inicia o LocalStack (SQS), o Mailpit e o serviço na porta `8083`. As filas não são criadas automaticamente; crie-as antes de iniciar a aplicação:

```powershell
docker compose up -d localstack mailpit
docker compose exec localstack awslocal sqs create-queue --queue-name approve-notification-queue-dev
docker compose exec localstack awslocal sqs create-queue --queue-name payment-notification-queue-dev
docker compose exec localstack awslocal sqs create-queue --queue-name callback-approve-notification-queue-dev
docker compose up --build -d notification-service
```

Para enviar uma decisão de aprovação de teste pela API no PowerShell:

```powershell
$orderId = "11111111-1111-4111-8111-111111111111"
Invoke-RestMethod -Method Post -Uri "http://localhost:8083/notifications/approval/$orderId" -ContentType "application/json" -Body '{"approved":true,"reason":"Orçamento aprovado"}'
Invoke-RestMethod -Method Get -Uri "http://localhost:8083/notifications/history"
```

A decisão é publicada na fila de callback; esse `POST` não gera um e-mail. Para inspecionar os e-mails capturados pelo Mailpit, acesse **http://localhost:8025**. A API fica em **http://localhost:8083**. Os destinatários gerados pelo serviço seguem o formato sintético `cliente-<customerId>@repairshop.com.br`; no ambiente local, o Mailpit captura as mensagens em vez de enviá-las a clientes reais. E-mails de aprovação e recibo são enviados quando eventos compatíveis chegam às respectivas filas.

Para acompanhar os logs: `docker compose logs --tail=100 notification-service`. Para encerrar os serviços: `docker compose down`.

## Estrutura do código

```text
src/main/kotlin/br/com/fiap/repairshop/notification/
├── NotificationServiceApplication.kt
└── infrastructure/
    ├── config/       # Configurações de integração
    ├── health/       # Indicador de saúde dos listeners SQS
    ├── messaging/    # Consumo de eventos, envio de e-mails e callback
    └── web/          # API REST de aprovação e histórico
```

## Container e Kubernetes

O `Dockerfile` usa etapas separadas de build e execução e configura o processo do container para rodar como usuário não-root (`10001`). No Deployment, o contexto de segurança também desabilita a escalação de privilégios, usa sistema de arquivos raiz somente para leitura e remove capabilities. O código fornece health checks para listeners SQS; os manifestos configuram probes HTTP de readiness e liveness, além de um HPA para CPU, de 1 a 3 réplicas, com alvo de utilização de 70%. São configurações versionadas no repositório; sua aplicação e efetividade dependem do ambiente Kubernetes em que forem implantadas.
