# Current state: what has actually been checked

Update this file whenever what is verified changes. A row moves up only with a command and a
result.

| | |
|---|---|
| Date | 2026-11-15 |
| Commit | none yet (repository initialised, nothing committed) |
| Environment | Cloud Linux workspace: Java 21 only, Maven Central unreachable, no Docker, Node 22, Python 3.13 |
| Verifier | Claude (AI assistant), unattended by a human engineer |

## Executed and passing

| What | Command | Result |
|---|---|---|
| model-gateway tests | `pytest -q` in `services/model-gateway` | 24 passed |
| ambient-ai tests | `pytest -q` in `services/ambient-ai` | 13 passed |
| Python lint | `ruff check .` in both services | clean |
| Web typecheck | `npx tsc --noEmit` in `web` | clean |
| Web lint | `npx eslint . --max-warnings 0` | clean |
| Web tests | `npx vitest run` | 11 passed in 2 files |
| Web production build | `npm run build` | built |
| Java syntax | JDK compiler parse of all 36 `.java` files | 0 syntax errors |
| Config files parse | `compose.yaml`, LiteLLM config, CI workflow, Keycloak realm JSON | valid YAML and JSON |
| Feature register | `tools/build_feature_register.py` | 553 rows, unique ids |

## Written but never executed

| What | Why not | How to check |
|---|---|---|
| `services/core` (compile, 15 tests) | Dependencies could not be downloaded; no Docker for Testcontainers | `mvn -B verify` with Java 25 and Docker |
| `services/api-gateway` (compile, 3 tests) | Same | Same |
| `compose.yaml` and Dockerfiles | No Docker | `docker compose --profile app up -d --build` |
| Image tags: `pgvector/pgvector:pg18`, `apache/kafka:4.1.0`, `minio/minio` and `minio/mc` release tags, `ghcr.io/speaches-ai/speaches:latest-cpu`, `ghcr.io/berriai/litellm:main-stable`, `ollama/ollama:latest` | Written from memory | Pull each; pin by digest once confirmed |
| Speaches model preload and LiteLLM transcription pass-through | Not run | First transcription through the stack |
| Keycloak realm import, tenant claim, service account role | Not run | Sign in as `dr.sara`; decode the token |
| Web app against a live backend, real microphone capture | No backend to run against | Golden-path walk in a browser |
| CI workflow | No remote repository yet | First push |

## Not done

- No end-to-end run of the golden path.
- No clinical evaluation of any model. Whisper and the Ollama model are unassessed for clinical English, Arabic, Gulf dialect or code-switching.
- No speaker separation on the local route.
- Arabic interface text and the consent wording were written by an AI and need review by a native-speaking clinician and by legal counsel.
- No multi-factor sign-in, rate limiting, tracing, metrics, backup or restore.
- model-gateway policy and execution ledger are in memory and are lost on restart.
- No FHIR export, documents, notifications or note amendment.

## Rule

By the blueprint's production-readiness rule (gate G18), this system is not production-ready
and must not be used with real patient data.
