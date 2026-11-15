# Afia Ambient

A UAE-first ambient clinical documentation platform: consented recording, Arabic and English
transcript, evidence-linked draft note, clinician review and sign-off.

**Status: Phase 0 skeleton. Synthetic or consented test data only. Not production-ready.**
The Java services have not yet been compiled or run; read
[docs/CURRENT_STATE.md](docs/CURRENT_STATE.md) before relying on anything.

| Read | For |
|---|---|
| [docs/PLAN.md](docs/PLAN.md) | What is being built, in what order, and what is decided |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | The five deployables, rules the code enforces, contracts |
| [docs/WORKFLOW.md](docs/WORKFLOW.md) | How a slice goes from spec to merged |
| [docs/CURRENT_STATE.md](docs/CURRENT_STATE.md) | What has actually been executed |
| [docs/features/register.yaml](docs/features/register.yaml) | All 553 requirements, work packages and features with status |
| [docs/adr/README.md](docs/adr/README.md) | Decisions |

## Layout

```
web/                     React + TypeScript clinician app
services/api-gateway/    edge: token check, CORS, route to core
services/core/           clinical records: patient, encounter, consent, media, transcript, note, audit
services/model-gateway/  the only path to a model: policy, registry, routing, ledger
services/ambient-ai/     recording -> transcript -> grounded draft
infra/                   Keycloak realm, LiteLLM config, Dockerfiles, scripts
compose.yaml             local stack
```

## Run it locally

Needs Docker with about 16 GB of memory for the local models, and several GB of disk for
model downloads on first start.

```bash
infra/scripts/init-env.sh                    # creates .env; prints the demo password
docker compose up -d                         # PostgreSQL, Keycloak, Kafka, MinIO, Whisper, Ollama, LiteLLM
docker compose --profile app up -d --build   # the five Afia deployables
```

Open http://localhost:5173 and sign in as `dr.sara` (clinician, tenant `demo-clinic`).
`dr.omar` is a clinician in a second tenant, for checking isolation. `auditor.demo` can read
the audit ledger. All three use the password printed by `init-env.sh`. These are synthetic
users for local development.

To use the offline synthetic models instead of Whisper and Ollama, remove
`AFIA_REGISTRY_PATH` and `AFIA_ROUTER_URL` from the `model-gateway` service in
`compose.yaml`. The synthetic model returns one fixed fictional consultation whatever is
recorded, and the web app labels it as a demonstration.

## Develop

```bash
mvn -B verify                                   # Java 25 + Docker (Testcontainers)
cd services/model-gateway && pip install -e ".[dev]" && ruff check . && pytest -q
cd services/ambient-ai   && pip install -e ".[dev]" && ruff check . && pytest -q
cd web && npm ci && npm run lint && npm run typecheck && npm test && npm run dev
```

Run services from the IDE against `docker compose up -d` using the defaults in each
`application.yml`; set `AFIA_DB_PASSWORD`, `AFIA_S3_ACCESS_KEY`, `AFIA_S3_SECRET_KEY` from
`.env`, and `AFIA_KAFKA_BOOTSTRAP=localhost:29092`.
