import json

import pytest
from fastapi import Header, HTTPException
from fastapi.testclient import TestClient

from model_gateway.auth import Caller
from model_gateway.main import create_app
from model_gateway.providers import ProviderError, StubAsr, StubLlm, default_providers

TOKENS = {
    "svc": Caller("svc-ambient-ai", frozenset({"svc-ambient"})),
    "ops": Caller("operator", frozenset({"platform-operator"})),
    "clinician": Caller("dr-a", frozenset({"clinician"})),
}


def fake_auth(authorization: str | None = Header(default=None)) -> Caller:
    caller = TOKENS.get((authorization or "").removeprefix("Bearer "))
    if caller is None:
        raise HTTPException(status_code=401, detail={"code": "unauthenticated"})
    return caller


SVC, OPS = {"Authorization": "Bearer svc"}, {"Authorization": "Bearer ops"}


@pytest.fixture
def client():
    return TestClient(create_app(authenticate=fake_auth))


def transcribe(client, tenant="tenant-a", request_id="r1", audio=b"RIFF-fake-audio", headers=SVC):
    return client.post("/v1/transcriptions", headers=headers,
                       data={"metadata": json.dumps({"tenant_id": tenant, "request_id": request_id})},
                       files={"audio": ("a.webm", audio, "audio/webm")})


def generate(client, segments, tenant="tenant-a", headers=SVC):
    return client.post("/v1/generations", headers=headers,
                       json={"tenant_id": tenant, "request_id": "g1", "segments": segments})


def test_transcription_returns_segments_and_provenance(client):
    res = transcribe(client)
    assert res.status_code == 200
    body = res.json()
    assert [s["seq"] for s in body["segments"]] == list(range(1, len(body["segments"]) + 1))
    assert {s["language"] for s in body["segments"]} == {"en", "ar"}
    assert all(s["end_ms"] > s["start_ms"] for s in body["segments"])
    prov = body["provenance"]
    assert prov["model_id"] == "stub-asr-v1" and prov["model_version"] == "1.0.0"
    assert prov["route"] == "primary" and prov["policy_version"]


def test_draft_statements_cite_existing_segments_and_carry_prompt_version(client):
    segments = transcribe(client).json()["segments"]
    res = generate(client, segments)
    assert res.status_code == 200
    body = res.json()
    assert [s["key"] for s in body["sections"]] == ["subjective", "objective", "assessment", "plan"]
    seqs = {s["seq"] for s in segments}
    cited = [e for sec in body["sections"] for st in sec["statements"] for e in st["evidence"]]
    assert cited and set(cited) <= seqs
    assert body["provenance"]["prompt_id"] == "soap-note-draft" and body["provenance"]["prompt_version"] == 1


def test_every_execution_is_traceable_in_the_ledger_without_content(client):
    execution_id = transcribe(client).json()["provenance"]["execution_id"]
    row = client.get(f"/v1/executions/{execution_id}", headers=OPS).json()
    assert row["tenant_id"] == "tenant-a" and row["request_id"] == "r1" and row["outcome"] == "completed"
    assert len(row["input_sha256"]) == 64 and len(row["output_sha256"]) == 64
    assert "headache" not in json.dumps(row)


def test_unauthenticated_and_wrong_role_are_rejected(client):
    assert transcribe(client, headers={}).status_code == 401
    assert transcribe(client, headers={"Authorization": "Bearer clinician"}).status_code == 403
    assert client.put("/v1/admin/kill-switch", headers=SVC, json={"engaged": True}).status_code == 403


def test_tenant_toggle_fails_closed_for_that_tenant_only(client):
    off = {"ai_enabled": False, "allowed_regions": ["local"], "disabled_use_cases": []}
    assert client.put("/v1/admin/tenants/tenant-a/policy", headers=OPS, json=off).status_code == 200
    res = transcribe(client, tenant="tenant-a")
    assert res.status_code == 403 and res.json()["code"] == "ai_disabled_for_tenant"
    assert transcribe(client, tenant="tenant-b").status_code == 200
    denied = [r for r in client.app.state.ledger.all() if r.outcome == "denied"]
    assert len(denied) == 1 and denied[0].tenant_id == "tenant-a" and denied[0].model_id is None


def test_use_case_can_be_disabled_per_tenant(client):
    body = {"ai_enabled": True, "allowed_regions": ["local"], "disabled_use_cases": ["clinical_note_draft"]}
    client.put("/v1/admin/tenants/tenant-a/policy", headers=OPS, json=body)
    segments = transcribe(client).json()["segments"]
    res = generate(client, segments)
    assert res.status_code == 403 and res.json()["code"] == "use_case_disabled_for_tenant"


def test_global_kill_switch_blocks_everything_and_can_be_released(client):
    client.put("/v1/admin/kill-switch", headers=OPS, json={"engaged": True})
    res = transcribe(client)
    assert res.status_code == 403 and res.json()["code"] == "ai_globally_disabled"
    client.put("/v1/admin/kill-switch", headers=OPS, json={"engaged": False})
    assert transcribe(client).status_code == 200


def test_residency_fails_closed_when_no_approved_region_is_available(client):
    body = {"ai_enabled": True, "allowed_regions": ["me-central-1"], "disabled_use_cases": []}
    client.put("/v1/admin/tenants/tenant-a/policy", headers=OPS, json=body)
    res = transcribe(client)
    assert res.status_code == 403 and res.json()["code"] == "no_compliant_route"


class FailingAsr:
    def __init__(self):
        self.calls = 0

    def transcribe(self, model, audio, content_type):
        self.calls += 1
        if model.id == "stub-asr-v1":
            raise ProviderError("primary down")
        return StubAsr().transcribe(model, audio, content_type)


def test_fallback_is_used_and_recorded_when_primary_fails():
    asr = FailingAsr()
    client = TestClient(create_app(authenticate=fake_auth, providers={"stub-asr": asr, "stub-llm": StubLlm()}))
    res = transcribe(client)
    assert res.status_code == 200
    prov = res.json()["provenance"]
    assert prov["route"] == "fallback" and prov["model_id"] == "stub-asr-fallback-v1" and asr.calls == 2
    row = client.app.state.ledger.get(prov["execution_id"])
    assert [a["error"] for a in row.attempts] == ["ProviderError", None]


class DeadAsr:
    def transcribe(self, model, audio, content_type):
        raise ProviderError("down")


def test_all_routes_failing_returns_503_and_is_recorded():
    client = TestClient(create_app(authenticate=fake_auth, providers={**default_providers(), "stub-asr": DeadAsr()}))
    res = transcribe(client)
    assert res.status_code == 503 and res.json()["code"] == "all_routes_failed"
    assert client.app.state.ledger.get(res.json()["execution_id"]).outcome == "failed"


def test_empty_audio_is_not_transcribed(client):
    assert transcribe(client, audio=b"").status_code == 503


def test_service_refuses_to_start_without_identity_configuration(monkeypatch):
    monkeypatch.delenv("AFIA_OIDC_JWKS_URL", raising=False)
    monkeypatch.delenv("AFIA_OIDC_ISSUER", raising=False)
    with pytest.raises(RuntimeError):
        create_app()
