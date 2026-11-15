# Architecture decisions

One short record per decision. Accepted decisions are not rewritten; a new record supersedes
an old one. The blueprint's own ADR list (its ADR-001 to ADR-030) is kept in
`docs/blueprint/`; the table at the end says how each is treated here.

## AFIA-001: New repository, old build not reused
**Status** Accepted, 2026-11-15, product owner. **Context** The earlier 14-service build
reports 2 of 104 features complete, 24 stubs and 78 partial, with most logic in one service.
**Decision** Start a clean repository. Nothing is copied from the old services.
**Consequences** Clean boundaries and honest status; the old DevSecOps pipeline has to be
re-added piece by piece. **Reopen** never; old code may be consulted as reference.

## AFIA-002: Five deployables in Phase 0
**Status** Accepted, product owner. **Decision** `web`, `api-gateway`, `core`, `model-gateway`,
`ambient-ai`. **Why** One to three engineers; the blueprint asks for a walking skeleton and
forbids services without a demonstrated need. **Consequences** One database and one release
for all clinical modules. **Reopen** when an extraction trigger in ARCHITECTURE.md section 3 occurs.

## AFIA-003: Modular core with one schema per module, enforced by a test
**Status** Accepted. **Decision** Modules are Java packages with their own PostgreSQL schema,
no cross-schema foreign keys, calls through service methods only; `ArchitectureRulesTest`
fails the build on a violation. **Consequences** Extraction later is a move, not a rewrite;
cross-module reads cost an extra query. **Reopen** if a module needs a join that an API cannot serve.

## AFIA-004: SQL with JdbcClient and Flyway, no ORM
**Status** Accepted. **Why** Tenant filters, guarded state transitions and immutability are
the safety properties; they should be readable in each statement. **Consequences** More SQL to
write and no lazy-loading surprises. **Reopen** if entity graphs become large (structured
clinical data, Phase 5).

## AFIA-005: Tenant only from the validated token
**Status** Accepted (blueprint ADR-010). **Decision** No endpoint accepts a tenant; the gateway
strips identity headers; another tenant's record answers "not found". Internal callbacks take
the tenant from the stored encounter.

## AFIA-006: Transactional outbox, idempotent callbacks, dead-letter topic
**Status** Accepted (blueprint ADR-007). **Decision** `Outbox.record` requires an open
transaction; a relay publishes in order, at least once; AI results carry idempotency keys
derived from the event id; unprocessable events go to `<topic>.dlq`. **Reopen** if relay
throughput becomes a limit (then consider change-data-capture).

## AFIA-007: Audit evidence in the same transaction, hash-chained per tenant
**Status** Accepted (narrows blueprint ADR-023). **Decision** In Phase 0 every audited action
writes its evidence in its own transaction (fail closed), because the ledger lives in the same
database. **Consequences** No separate audit outage mode yet. **Reopen** when `audit` is
extracted; then apply the three delivery classes from the blueprint.

## AFIA-008: model-gateway is the only path to a model, enforced three ways
**Status** Accepted (blueprint ADR-011, ADR-012). **Decision** Code: no provider SDK in core
(test) and only a gateway client in ambient-ai. Network: models and router on an internal
network that only model-gateway joins. Policy: kill switch, tenant and use-case toggles,
region rule, registry status, all failing closed.

## AFIA-009: Local open-source models first; no synthetic fallback for real audio
**Status** Accepted, product owner. **Decision** Whisper and an Ollama model behind LiteLLM.
The synthetic providers exist only in the test registry. **Consequences** Quality and speed
depend on local hardware; nothing leaves the machine. **Reopen** after the Phase 3 evaluation,
or when a cloud region is approved.

## AFIA-010: Keycloak holds users, roles and tenant attribute
**Status** Accepted for Phases 0 and 1. **Consequences** No tenant administration screens yet;
administrators use the Keycloak console. **Reopen** Phase 2 (tenant administration).

## AFIA-011: No Redis, workflow engine, schema registry or search engine yet
**Status** Accepted (blueprint ADR-024, 026, 027). Each is added only by a new ADR that names
the requirement it serves.

## AFIA-012: Evidence is checked twice and unsupported statements block sign-off
**Status** Accepted. **Decision** ambient-ai drops evidence that does not exist in the
transcript; core re-checks and marks the statement unsupported; the clinician must edit or
remove it before signing. **Consequences** A model cannot make an unsupported line look
grounded. **Limit** This proves a cited segment exists, not that the line is a faithful
reading of it; that is the job of the Phase 3 evaluation harness.

## Blueprint decisions

| Blueprint ADR | Treatment |
|---|---|
| 001 UAE first; 002 React/TypeScript/Vite; 003 Java + Python; 004 PostgreSQL; 009 Keycloak | Adopted unchanged |
| 005 service-owned data | Adopted as module-owned schemas (AFIA-003) |
| 006, 007 Kafka, outbox, idempotent consumers | Adopted (AFIA-006) |
| 008 object storage and claim check | Adopted: audio by reference only |
| 010 tenant from token | Adopted (AFIA-005) |
| 011 to 014, 019, 020 model-gateway, router, registry, residency, kill switch | Adopted (AFIA-008, AFIA-009) |
| 015, 016, 018 separate AI data plane, datasets, evaluation gates | Adopted as intent; built in Phase 3 |
| 017 clinician sign-off | Adopted (AFIA-012 and the sign-off gate) |
| 021 document service; 022 notification service | Adopted as modules in core in Phase 2, extracted on a trigger |
| 023 audit delivery classes | Narrowed for now (AFIA-007) |
| 024 to 027 Redis, feature flags, workflow engine, search | Deferred (AFIA-011) |
| 028 analytics as projections; 029 secret manager; 030 OpenTelemetry | Adopted as intent; Phase 1 and later |
