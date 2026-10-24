# 📢 Tech Challenge RepairShop - Notification Service

> **Microsserviço de Notificações, Comunicações e Feedback de Aprovação**  
> **Tech Challenge SOAT (Fase 4)**  
> **Organização GitHub:** `tech-challenge-fiap-repairshop`

---

## 📌 Visão Geral

O **`notification-service`** é o microsserviço responsável pelo envio de comunicações aos clientes (e-mails, alertas e mensagens sobre orçamentos e pagamentos) e captura da resposta interativa do cliente para aprovação ou recusa do orçamento da oficina mecânica.

---

## 🏛️ Arquitetura e Responsabilidades

- **Padrão de Arquitetura:** Clean Architecture / Event-Driven.
- **Consumo de Mensageria (AWS SQS):**
  - `SQS approve-notification-queue`: Notificações de orçamento pronto para revisão do cliente.
  - `SQS payment-notification-queue`: Notificações de faturamento gerado e recibos de pagamento emitidos.
- **Endpoint Interativo do Cliente:**
  - `POST /approval/{service_order_id}`: Recebe a decisão do cliente (`APPROVED` ou `REFUSED`) com justificativa opcional.
- **Publicação na Saga:**
  - Publica o resultado da decisão na fila `SQS callback-approve-notification-queue` para retorno ao `service_order_execution-service`.

---

## 📂 Estrutura de Diretórios

```
tech-challenge-repairshop-notification-service/
├── .github/workflows/         # Pipelines de CI/CD (GitHub Actions)
├── k8s/                       # Manifestos Kubernetes (Deployment, Service, HPA, ConfigMap)
├── src/
│   ├── main/
│   │   ├── kotlin/com/cao/repairshop/notification/
│   │   │   ├── domain/        # Entidades de Notificação e Mensagens
│   │   │   ├── application/   # Casos de Uso (SendApprovalRequest, ProcessClientFeedback)
│   │   │   ├── infrastructure/# SQS Consumers/Producers, Mail Sender/Push Gateway
│   │   │   └── interfaces/    # Controller REST para aprovação do cliente
│   │   └── resources/         # application.properties e Templates de E-mail
│   └── test/                  # Testes Unitários e Testes BDD (Cucumber)
├── Dockerfile                 # Multi-stage Docker build
└── README.md
```

---

## 🛡️ Governança de Branches
- `main`: Branch de produção (protegida contra push direto).
- `homolog`: Branch de homologação e testes integrados.
- `develop`: Branch de desenvolvimento ativo.
- `feat/*`, `fix/*`, `chore/*`: Branches de trabalho.
