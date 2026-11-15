# <ID>: <title>

| | |
|---|---|
| Register rows | <ids from docs/features/register.yaml> |
| Phase | <0-6> |
| Owner | <name> |
| Status | DRAFT / APPROVED / DONE |
| Depends on | <slice ids> |

## Why
The problem, who has it, and what is true when this is done. What is out of scope.

## Behaviour
Main flow, alternative flows and failure flows. State transitions and their guards.

## Safety and security
Roles allowed. Tenant handling. Consent. Clinical content touched and where it may go.
Audit class: written with the action (fail closed), outbox, or telemetry only.

## Data and contracts
Tables and owning module. Endpoints (method, path, role, error codes). Events (name, fields,
consumer). Idempotency key. Migration file.

## Operations
Timeouts, retries, metrics, alerts, what an operator does when it fails.

## Verification
Tests to write: unit, integration, negative (tenant, role, consent, state), duplicate or
redelivery, and the end-to-end step if the flow changes. Commands and expected results.

## Change impact
Compatibility, rollback, documents and ADRs to update.
