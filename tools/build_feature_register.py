#!/usr/bin/env python3
"""Builds docs/features/register.yaml from the master blueprint.

    pandoc docs/blueprint/Afia_master_blueprint.docx -t gfm --wrap=none -o /tmp/blueprint.md
    python3 tools/build_feature_register.py /tmp/blueprint.md

Every requirement, work package and feature in the blueprint becomes one row with our
scope classification, delivery phase and implementation status. Status is never taken from
the blueprint: anything not listed in IMPLEMENTED below is NOT_IMPLEMENTED.
"""
import re
import sys
from collections import Counter
from pathlib import Path

# family prefix -> (family name, classification, delivery phase)
FAMILIES = {
    "FIN": ("Patient finance and revenue cycle", "FUTURE_OPTION", 6),
    "SCH": ("Scheduling, appointments and queues", "INTEGRATE_EXTERNAL", 6),
    "PRC": ("Practitioner and clinical organisation", "SUPPORTING_AFIA", 2),
    "TRM": ("Clinical terminology and master data", "SUPPORTING_AFIA", 3),
    "SCD": ("Medications, allergies, diagnoses and problems", "CORE_AFIA", 5),
    "ORD": ("Orders, results and referrals", "INTEGRATE_EXTERNAL", 6),
    "PAT": ("Patient portal and communications", "FUTURE_OPTION", 6),
    "TSK": ("Care tasks and follow-up", "CORE_AFIA", 5),
    "TPL": ("Clinical templates and forms", "CORE_AFIA", 3),
    "COD": ("Medical coding assistance", "CORE_AFIA", 5),
    "ONB": ("Tenant onboarding and go-live", "SUPPORTING_AFIA", 4),
    "SAA": ("Afia SaaS subscription and usage billing", "FUTURE_OPTION", 6),
    "SAF": ("Clinical safety and human factors", "CORE_AFIA", 4),
    "AIM": ("AI and data incident management", "CORE_AFIA", 4),
    "BCP": ("Business continuity without AI", "CORE_AFIA", 2),
    "SUP": ("Support and service management", "INTEGRATE_EXTERNAL", 6),
    "GOV": ("Licensing, vendors and subprocessors", "SUPPORTING_AFIA", 4),
    "EXT": ("Tenant exit and data portability", "CORE_AFIA", 6),
    "DQP": ("Data quality, media pipeline and API platform", "SUPPORTING_AFIA", 2),
}
# WBS group -> (family name, classification, delivery phase)
WBS = {
    "1.1": ("Model gateway and AI control plane", "CORE_AFIA", 0),
    "1.2": ("Evidence-linked clinical documentation", "CORE_AFIA", 0),
    "1.3": ("Clinical AI evaluation framework", "CORE_AFIA", 3),
    "2.1": ("Arabic and multilingual clinical intelligence", "CORE_AFIA", 3),
    "2.2": ("Production EHR integration", "CORE_AFIA", 2),
    "2.3": ("UAE compliance and procurement readiness", "CORE_AFIA", 4),
    "3.1": ("Controlled clinical pilot", "CORE_AFIA", 4),
    "3.2": ("Production hardening", "CORE_AFIA", 4),
    "3.3": ("Enterprise platformisation", "SUPPORTING_AFIA", 6),
    "4.1": ("Low-risk workflow intelligence", "CORE_AFIA", 5),
    "4.2": ("Bounded clinical agents", "FUTURE_OPTION", 6),
    "5.2": ("Platform regionalisation", "FUTURE_OPTION", 6),
    "7.1": ("Platform foundation and reliability", "SUPPORTING_AFIA", 1),
    "8.1": ("Document management", "SUPPORTING_AFIA", 2),
    "9.1": ("Notifications", "SUPPORTING_AFIA", 2),
}
# id -> (status, evidence). Java evidence is written and syntax-checked but not yet compiled or run.
J = "IMPLEMENTED_NOT_RUNTIME_VERIFIED"
P = "PARTIALLY_IMPLEMENTED"
V = "IMPLEMENTED_AND_TEST_VERIFIED"
IMPLEMENTED = {
    "1.1.1": (V, "services/model-gateway main.py; tests/test_gateway.py"),
    "1.1.2": (V, "model-gateway PolicyStore tenant toggle; test_tenant_toggle_fails_closed_for_that_tenant_only"),
    "1.1.3": (V, "model-gateway registry.yaml, registry.local.yaml"),
    "1.1.4": (V, "model-gateway prompt registry with versions and approved models"),
    "1.1.5": (P, "model-gateway router.py (LiteLLM adapter, tested against a fake router; not run against LiteLLM)"),
    "1.1.6": (V, "model-gateway routes per use case"),
    "1.1.7": (V, "test_fallback_is_used_and_recorded_when_primary_fails"),
    "1.1.8": (V, "test_residency_fails_closed_when_no_approved_region_is_available"),
    "1.1.9": (P, "registry.local.yaml routes to local Whisper and Ollama; stack not run here"),
    "1.1.10": (P, "model-gateway execution ledger (in memory; durable store is Phase 1)"),
    "1.1.12": (P, "latency and attempts recorded per execution; no metrics export yet"),
    "1.1.13": (P, "JWT service auth, role checks; secrets from environment only"),
    "1.1.14": (P, "core ArchitectureRulesTest; compose 'models' internal network"),
    "1.1.15": (V, "24 model-gateway tests"),
    "1.2.1": (J, "core TranscriptService.store; ambient-ai pipeline"),
    "1.2.2": (P, "ambient-ai pipeline + model-gateway draft; SOAP only"),
    "1.2.4": (J, "core NoteService evidence re-check; ambient-ai ground()"),
    "1.2.5": (J, "transcript.segment start_ms/end_ms; statement evidence_seqs"),
    "1.2.6": (J, "statement confidence stored and returned"),
    "1.2.7": (J, "unsupported statements flagged and block sign-off"),
    "1.2.8": (P, "web Review.tsx (typecheck, lint, 11 tests, build pass; not run against a live backend)"),
    "1.2.9": (J, "core NoteService.review ACCEPT/EDIT/REJECT; web StatementView"),
    "1.2.10": (J, "core NoteService.sign with attestation"),
    "1.2.11": (J, "copy-on-write note versions"),
    "1.2.12": (J, "Provenance stored on transcript and note"),
    "1.2.13": (J, "core AuditService hash chain; GoldenPathIT"),
    "7.1.4": (J, "core platform Outbox + OutboxRelay; SafetyAndIsolationIT relay test"),
    "FR-TEN-001": (J, "tenant from token only; SafetyAndIsolationIT"),
    "FR-IAM-001": (J, "core and gateway SecurityConfig; Keycloak realm"),
    "FR-CON-001": (J, "consent gate before capture and AI; revocation quarantines audio"),
    "FR-ENC-001": (J, "core EncounterService state machine"),
    "FR-MED-001": (P, "single-file upload inside a consented encounter; resumable upload not yet"),
    "FR-ASR-001": (P, "timestamped segments with per-segment language; no measured thresholds"),
    "FR-AI-001": (P, "architecture test, network isolation, no provider SDK outside model-gateway"),
    "FR-AI-002": (V, "model-gateway tenant toggle, use-case toggle, kill switch, residency tests"),
    "FR-AI-003": (J, "provenance on every transcript and draft"),
    "FR-NOT-001": (J, "evidence-linked draft statements"),
    "FR-NOT-002": (J, "sign-off gate; signed note immutable in the database"),
    "FR-AUD-001": (J, "append-only hash-chained audit ledger per tenant"),
    "FR-ID-004": (P, "web PatientBanner always visible in the encounter"),
    "FR-ID-005": (J, "patientConfirmed required to start a recording"),
    "FR-ID-006": (J, "recording, transcript and note bound to encounter id"),
    "FR-ID-007": (J, "unique index: one active recording per encounter"),
    "NFR-AVL-002": (J, "manual note path when AI is unavailable"),
    "NFR-REL-002": (J, "idempotency keys on transcript and draft; ambient-ai redelivery test"),
    "NFR-L10N-001": (P, "Arabic/English UI with RTL; Arabic text needs native and legal review"),
    "FEAT-SAF-009": (J, "draft banner; signed state; server-side immutability"),
    "FEAT-SAF-014": (P, "consent gate and recording indicator in the web app"),
    "FEAT-SAF-015": (P, "evidence chips and not-in-transcript flag in the web app"),
    "FEAT-AIM-009": (V, "model-gateway per-tenant and per-use-case disable"),
    "FEAT-AIM-010": (V, "model-gateway global kill switch"),
    "FEAT-BCP-001": (J, "core NoteService.createManual; web 'Write the note by hand'"),
    "FEAT-BCP-004": (P, "ambient-ai bounded retry then processing-failed; manual path"),
    "FEAT-BCP-005": (P, "no synthetic fallback for real audio; failure reported"),
    "FEAT-BCP-011": (P, "encounter ai_status shown in the web app"),
}


def cells(line: str) -> list[str]:
    return [c.strip().replace("\\", "") for c in line.strip().strip("|").split("|")]


def q(text: str) -> str:
    return '"' + text.replace('"', "'") + '"'


def main() -> None:
    lines = Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
    rows: list[dict] = []
    seen: set[str] = set()

    def add(id_: str, name: str, family: str, cls: str, phase: int, priority: str = "", owner: str = "") -> None:
        if id_ in seen:
            return
        seen.add(id_)
        status, evidence = IMPLEMENTED.get(id_, ("NOT_IMPLEMENTED", ""))
        rows.append({"id": id_, "name": name, "family": family, "classification": cls, "phase": phase,
                     "priority": priority, "blueprint_owner": owner, "status": status, "evidence": evidence})

    for line in lines:
        if m := re.match(r"\| (FEAT-([A-Z]+)-\d+) \|", line):
            c = cells(line)
            family, cls, phase = FAMILIES[m.group(2)]
            fin = m.group(2) == "FIN"
            add(c[0], c[1], family, cls, phase, priority=c[4] if fin else c[3], owner=c[3] if fin else c[2])
        elif m := re.match(r"\| ((?:FR|NFR)-[A-Z0-9]+-\d+) \|", line):
            c = cells(line)
            name = c[2] if c[0].startswith("NFR") else c[1]
            kind = "Non-functional requirements" if c[0].startswith("NFR") else "Functional requirements"
            add(c[0], name, kind, "CORE_AFIA", 1 if c[0].startswith("NFR") else 0, priority="P0")
        elif m := re.match(r"(?:\| |- )(\d\.\d)\.(\d+) (?:\| )?(.+)", line):
            group = m.group(1)
            if group in WBS:
                family, cls, phase = WBS[group]
                add(f"{group}.{m.group(2)}", cells("|" + m.group(3))[0], family, cls, phase)

    out = ["# Generated by tools/build_feature_register.py from docs/blueprint. Edit the script, not this file.",
           "# status: NOT_IMPLEMENTED | PARTIALLY_IMPLEMENTED | IMPLEMENTED_NOT_RUNTIME_VERIFIED |",
           "#         IMPLEMENTED_AND_TEST_VERIFIED | IMPLEMENTED_AND_RUNTIME_VERIFIED",
           "features:"]
    for r in rows:
        out.append(f"  - id: {r['id']}")
        for key in ("name", "family", "classification", "priority", "blueprint_owner", "status", "evidence"):
            if r[key]:
                out.append(f"    {key}: {q(r[key])}")
        out.append(f"    phase: {r['phase']}")
    Path("docs/features/register.yaml").write_text("\n".join(out) + "\n", encoding="utf-8")

    by_status, by_phase, by_class = (Counter(r[k] for r in rows) for k in ("status", "phase", "classification"))
    print(len(rows), "rows")
    for name, counter in (("status", by_status), ("phase", by_phase), ("classification", by_class)):
        print(name, dict(sorted(counter.items(), key=lambda kv: str(kv[0]))))
    missing = set(IMPLEMENTED) - seen
    if missing:
        raise SystemExit(f"IMPLEMENTED ids not found in the blueprint: {sorted(missing)}")


if __name__ == "__main__":
    main()
