# Delivery workflow

How a piece of the plan becomes merged, verified code. It is written for a small team working
with AI coding agents: a worker agent implements, a reviewer agent checks, the product owner
approves and merges.

## Roles

| Role | Who | Does |
|---|---|---|
| Product owner and architecture authority | Muhammad Siddique | Chooses the next slice, approves specs and ADRs, merges |
| Worker | An AI coding agent (for example Gemini through Antigravity) or an engineer | Implements exactly one slice on a branch |
| Reviewer | A second agent (for example Claude Code) that did not write the code | Reviews the diff against the spec and the rules below; never merges |
| CI | GitHub Actions (manual trigger only for now; until it is back on, the gates below are run locally and the results pasted into the pull request) | The judge of "it works"; nobody overrides a red build |

## The loop, one slice at a time

1. **Pick.** Take the first unfinished item in `docs/PLAN.md` section 9, or the next row for the current phase in `docs/features/register.yaml`. One slice is one behaviour a user or operator can observe, small enough to review in one sitting.
2. **Specify.** Copy `docs/specs/TEMPLATE.md` to `docs/specs/<id>-<slug>.md` and fill it in. Phase-critical (P0) slices need the product owner's approval of the spec before code is written.
3. **Branch.** `feat/<id>-<slug>` from `main`. One slice per branch.
4. **Build.** Write the failing test first where practical, then the code. Change only what the spec names. If the slice needs a decision the ADR index does not cover, stop and write the ADR.
5. **Verify locally.** Run the gates below. Paste the commands and results into the pull request.
6. **Review.** The reviewer checks the diff against the spec, the rules in `CLAUDE.md` and the architecture rules in `docs/ARCHITECTURE.md` section 2. Findings are fixed on the same branch.
7. **Merge.** The product owner merges when CI is green and the review is clean.
8. **Record.** In the same pull request: update the row's status and evidence in `tools/build_feature_register.py` and regenerate the register; update `docs/CURRENT_STATE.md` if what is verified changed; update the diagram or contract tables in `docs/ARCHITECTURE.md` if a boundary, endpoint or event changed.

## Gates

| Gate | Command | Applies to |
|---|---|---|
| Java build, unit and integration tests | `mvn -B verify` | `services/core`, `services/api-gateway` |
| Python lint and tests | `ruff check . && pytest -q` in each Python service | `services/model-gateway`, `services/ambient-ai` |
| Web | `npm run lint && npm run typecheck && npm test && npm run build` in `web` | `web` |
| Secrets and dependencies | gitleaks and trivy in CI | everything |
| Golden path | Browser walk today; Playwright from slice P1-003 | any change to the flow |

## Status words

Use exactly these in the register. A status is a statement about evidence, not about effort.

| Status | Meaning |
|---|---|
| `NOT_IMPLEMENTED` | No code |
| `PARTIALLY_IMPLEMENTED` | Some behaviour exists; the evidence column says what is missing |
| `IMPLEMENTED_NOT_RUNTIME_VERIFIED` | Code and tests are written; the tests have not run green in CI |
| `IMPLEMENTED_AND_TEST_VERIFIED` | Automated tests pass in CI, or locally with the command and result recorded in CURRENT_STATE.md |
| `IMPLEMENTED_AND_RUNTIME_VERIFIED` | Also observed working in the running stack, recorded in CURRENT_STATE.md |

## Definition of done

- The spec, the code, the tests and the observed behaviour agree.
- Tests use real PostgreSQL (Testcontainers); mocks alone do not prove a database or event rule.
- Negative tests exist for tenant isolation, role, consent and state where the slice touches them.
- A repeated request or redelivered event has no second effect.
- No clinical content or secret appears in logs, events, audit rows, error bodies or the browser console.
- New configuration is in `.env.example` with a placeholder, never a real value.
- Register, current-state record and architecture document are updated in the same pull request.

## Things a slice must never do

- Call a model, the router or a model vendor from anywhere except `services/model-gateway`.
- Accept a tenant from a path, query, header or body.
- Make AI output final, or export it, without a clinician's sign-off.
- Change a signed note or an audit row. Corrections are amendments; the ledger is append-only.
- Edit an existing Flyway migration. Add a new one.
- Write to the database and to Kafka separately. Use the outbox.
- Let a module in `core` read or write another module's schema.
- Disable, skip or weaken a failing test to get a green build.
- Use real patient data anywhere outside an approved pilot environment.
- Mark a row implemented from file names, interfaces or tests that have not run.
