"""Pipeline tests run against the real model-gateway application in-process, so the
contract between the two services is exercised, not mocked. Core and storage are fakes."""
import pytest
from fastapi import Header
from fastapi.testclient import TestClient
from model_gateway.auth import Caller
from model_gateway.main import create_app
from model_gateway.providers import ProviderError, default_providers

from ambient_ai.clients import ModelGatewayClient
from ambient_ai.pipeline import RecordingJob, Retryable, ground, handle_event


def svc_auth(authorization: str | None = Header(default=None)) -> Caller:
    return Caller("svc", frozenset({"svc-ambient", "platform-operator"}))


class FakeStorage:
    def __init__(self, fail_times=0):
        self.fail_times = fail_times

    def get(self, bucket, key):
        if self.fail_times > 0:
            self.fail_times -= 1
            raise Retryable("storage down")
        return b"synthetic-audio"


class FakeCore:
    """Records calls and deduplicates by idempotency key, as core does."""

    def __init__(self):
        self.transcripts, self.drafts, self.failures, self.correlations = {}, {}, {}, set()

    def submit_transcript(self, encounter_id, payload, correlation_id):
        self.transcripts.setdefault(payload["idempotencyKey"], (encounter_id, payload))
        self.correlations.add(correlation_id)

    def submit_draft(self, encounter_id, payload, correlation_id):
        self.drafts.setdefault(payload["idempotencyKey"], (encounter_id, payload))

    def report_failure(self, encounter_id, payload, correlation_id):
        self.failures.setdefault(payload["idempotencyKey"], (encounter_id, payload))


def envelope(event_type="RecordingCompleted", event_id="evt-1"):
    return {"eventId": event_id, "eventType": event_type, "schemaVersion": 1, "tenantId": "tenant-a",
            "aggregateType": "Encounter", "aggregateId": "enc-1", "aggregateVersion": 5,
            "correlationId": "corr-1", "causationId": None, "occurredAt": "2026-11-15T10:00:00Z",
            "producer": "core", "classification": "INTERNAL",
            "data": {"encounterId": "enc-1", "recordingId": "rec-1", "bucket": "afia-media",
                     "objectKey": "tenant-a/enc-1/rec-1.webm", "contentType": "audio/webm"}}


@pytest.fixture
def gw_app():
    return create_app(authenticate=svc_auth)


@pytest.fixture
def gateway(gw_app):
    return ModelGatewayClient(TestClient(gw_app))


def test_recording_becomes_transcript_and_grounded_draft(gateway):
    core = FakeCore()
    assert handle_event(envelope(), FakeStorage(), gateway, core) == "draft_ready"
    encounter_id, transcript = core.transcripts["evt-1:transcript"]
    assert encounter_id == "enc-1" and transcript["recordingId"] == "rec-1"
    assert {"seq", "speaker", "language", "text", "startMs", "endMs", "confidence"} == set(transcript["segments"][0])
    assert transcript["provenance"]["modelId"] == "stub-asr-v1" and transcript["provenance"]["executionId"]
    _, draft = core.drafts["evt-1:draft"]
    assert draft["provenance"]["promptId"] == "soap-note-draft" and draft["provenance"]["promptVersion"] == 1
    seqs = {s["seq"] for s in transcript["segments"]}
    statements = [st for sec in draft["sections"] for st in sec["statements"]]
    assert all(set(st["evidenceSeqs"]) <= seqs for st in statements)
    unsupported = [st for st in statements if not st["supported"]]
    assert [st["text"] for st in unsupported] == ["No known drug allergies."]
    assert core.correlations == {"corr-1"} and not core.failures


def test_redelivered_event_does_not_create_a_second_transcript_or_draft(gateway):
    core = FakeCore()
    handle_event(envelope(), FakeStorage(), gateway, core)
    handle_event(envelope(), FakeStorage(), gateway, core)
    assert len(core.transcripts) == 1 and len(core.drafts) == 1


def test_other_event_types_are_ignored(gateway):
    core = FakeCore()
    assert handle_event(envelope(event_type="EncounterCreated"), FakeStorage(), gateway, core) == "ignored"
    assert not core.transcripts and not core.failures


def test_unknown_schema_version_is_rejected():
    bad = envelope() | {"schemaVersion": 2}
    with pytest.raises(ValueError):
        RecordingJob.from_event(bad)


def test_tenant_ai_disabled_reports_failure_and_submits_nothing(gw_app, gateway):
    gw_app.state.policy.set_tenant("tenant-a", ai_enabled=False, allowed_regions=["local"], disabled_use_cases=[])
    core = FakeCore()
    assert handle_event(envelope(), FakeStorage(), gateway, core) == "ai_unavailable"
    assert not core.transcripts and not core.drafts
    assert core.failures["evt-1:failed"][1]["reasonCode"] == "ai_disabled_for_tenant"


def test_draft_disabled_mid_encounter_keeps_transcript_and_reports_failure(gw_app, gateway):
    gw_app.state.policy.set_tenant("tenant-a", ai_enabled=True, allowed_regions=["local"],
                                   disabled_use_cases=["clinical_note_draft"])
    core = FakeCore()
    assert handle_event(envelope(), FakeStorage(), gateway, core) == "ai_unavailable"
    assert len(core.transcripts) == 1 and not core.drafts
    assert core.failures["evt-1:failed"][1]["reasonCode"] == "use_case_disabled_for_tenant"


def test_transient_storage_failure_is_retried(gateway):
    core, waits = FakeCore(), []
    assert handle_event(envelope(), FakeStorage(fail_times=2), gateway, core, sleep=waits.append) == "draft_ready"
    assert waits == [2, 4] and len(core.drafts) == 1


def test_exhausted_retries_report_failure_instead_of_hanging(gateway):
    core = FakeCore()
    assert handle_event(envelope(), FakeStorage(fail_times=99), gateway, core) == "failed"
    assert core.failures["evt-1:failed"][1]["reasonCode"] == "processing_failed" and not core.drafts


class DeadAsr:
    def transcribe(self, model, audio, content_type):
        raise ProviderError("down")


def test_all_model_routes_down_is_retryable_then_reported():
    app = create_app(authenticate=svc_auth, providers={**default_providers(), "stub-asr": DeadAsr()})
    core = FakeCore()
    assert handle_event(envelope(), FakeStorage(), ModelGatewayClient(TestClient(app)), core) == "failed"
    assert core.failures and not core.transcripts


def test_grounding_drops_invalid_evidence_and_flags_the_statement():
    sections = [{"key": "plan", "title": "Plan", "statements": [
        {"text": "Start amoxicillin.", "evidence": [99], "confidence": 0.8},
        {"text": "Review in a week.", "evidence": [2, 2, 99], "confidence": 0.9}]}]
    out = ground(sections, {1, 2, 3})[0]["statements"]
    assert out[0] == {"text": "Start amoxicillin.", "evidenceSeqs": [], "supported": False, "confidence": 0.8}
    assert out[1]["evidenceSeqs"] == [2] and out[1]["supported"] is True


def test_results_refused_by_core_are_final_and_not_reported_as_ai_failure(gateway):
    from ambient_ai.pipeline import Rejected

    class RevokedCore(FakeCore):
        def submit_transcript(self, encounter_id, payload, correlation_id):
            raise Rejected("consent_required")

    core = RevokedCore()
    assert handle_event(envelope(), FakeStorage(), gateway, core) == "rejected_by_core"
    assert not core.drafts and not core.failures


def test_core_conflict_maps_to_rejected_and_server_errors_to_retryable():
    import httpx

    from ambient_ai.clients import CoreClient
    from ambient_ai.pipeline import Rejected

    def client(status, body):
        transport = httpx.MockTransport(lambda request: httpx.Response(status, json=body))
        return CoreClient(httpx.Client(base_url="http://core.test", transport=transport))

    with pytest.raises(Rejected) as rejected:
        client(409, {"code": "consent_required"}).submit_draft("e1", {}, "corr-1")
    assert rejected.value.code == "consent_required"
    with pytest.raises(Retryable):
        client(503, {}).submit_draft("e1", {}, "corr-1")
    client(204, None).submit_draft("e1", {}, "corr-1")


def test_a_bad_event_goes_to_the_dead_letter_topic_and_does_not_stop_the_consumer():
    import asyncio

    from ambient_ai.worker import handle_message

    dead = []

    async def dead_letter(value, reason):
        dead.append((value, reason))

    def explode(envelope):
        raise KeyError("data")

    async def run():
        return [await handle_message(b"not json", lambda e: "draft_ready", dead_letter),
                await handle_message(b"[1, 2]", lambda e: "draft_ready", dead_letter),
                await handle_message(b'{"eventId": "e-9"}', explode, dead_letter),
                await handle_message(b'{"eventId": "e-10"}', lambda e: "draft_ready", dead_letter)]

    assert asyncio.run(run()) == ["dead_lettered", "dead_lettered", "dead_lettered", "draft_ready"]
    assert [reason for _, reason in dead] == ["unreadable_event", "unreadable_event", "KeyError"]
