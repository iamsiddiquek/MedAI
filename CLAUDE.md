# Agent entry point: Afia Ambient

Read `docs/WORKFLOW.md` before changing anything. One slice per branch, with a spec in
`docs/specs/`. Status claims follow `docs/CURRENT_STATE.md`: never mark work done from code
that has not run.

Never:
- call a model, router or model vendor outside `services/model-gateway`;
- accept a tenant from a request; it comes from the token only;
- make AI output final or export it without clinician sign-off;
- change a signed note, an audit row or an existing Flyway migration;
- write to the database and Kafka separately (use `Outbox`);
- let a `core` module touch another module's schema;
- put clinical content or secrets in logs, events, audit rows, error bodies or the console;
- skip, disable or weaken a test to get a green build;
- commit `.env` or any real secret; use real patient data.

Gates: `mvn -B verify`; `ruff check . && pytest -q` in each Python service;
`npm run lint && npm run typecheck && npm test && npm run build` in `web`.

In the same change as the code: update `tools/build_feature_register.py` and regenerate
`docs/features/register.yaml`, and update `docs/ARCHITECTURE.md` section 6 when an endpoint
or event changes.
