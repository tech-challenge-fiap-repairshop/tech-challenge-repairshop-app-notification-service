# language: en

Feature: Customer budget decision
  So the repair shop knows whether it can perform the service
  As a customer
  I want to submit my decision and receive confirmation

  Scenario: Customer approves the budget
    Given the service order has a valid identifier
    When the customer approves the budget without a reason
    Then the decision is confirmed and published to the callback queue

  Scenario: Customer rejects the budget with a reason
    Given the service order has a valid identifier
    When the customer rejects the budget with reason "Price is higher than expected"
    Then the decision is confirmed and published to the callback queue

  Scenario: Invalid identifier is rejected
    When a decision is submitted for an invalid identifier
    Then the request is rejected and no decision is published
