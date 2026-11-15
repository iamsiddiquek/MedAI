# P1-001: first green build of the Java services

| | |
|---|---|
| Register rows | all rows with status IMPLEMENTED_NOT_RUNTIME_VERIFIED |
| Phase | 1 |
| Owner | unassigned |
| Status | APPROVED |
| Depends on | none |

## Why
`services/core` and `services/api-gateway` were written where Java dependencies could not be
downloaded and Docker was not available. They pass a syntax-only parse; they have never been
compiled, and none of their 18 tests has run. Until `mvn verify` is green, Phase 0 is not done.

## Behaviour
No behaviour change. Fix only what stops compilation or makes an existing test fail. If a test
fails because the code is wrong, fix the code; if the test's expectation is wrong, say why in
the pull request. Do not delete or skip a test.

## Verification
`mvn -B verify` from the repository root with Java 25 and Docker running. Expected: both
modules build; `ArchitectureRulesTest`, `GoldenPathIT`, `SafetyAndIsolationIT` and
`GatewayEdgeTest` pass. Then set the affected register rows to IMPLEMENTED_AND_TEST_VERIFIED
and record the commit and result in `docs/CURRENT_STATE.md`.

## Likely trouble spots
Spring Boot 4 starter and test package names; Testcontainers 2 module names and the
`jdbc:tc:` URL; `JdbcClient` binding of `uuid`, `integer[]` and list parameters; the gateway
test's route and CORS assertions.
