"""The ambient pipeline: recording -> transcript -> grounded draft.

This service owns no records. It reads audio by reference, asks the model-gateway for
every model call, and submits results to core with idempotency keys derived from the
event id, so a redelivered event cannot create a second transcript or draft.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

EVENT_TYPE = "RecordingCompleted"


class GatewayDenied(Exception):
    """Policy refused the call (kill switch, tenant toggle, residency). Not retryable."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class Retryable(Exception):
    """A dependency was unavailable; the same event may be processed again."""


class Rejected(Exception):
    """Core refused the result (for example consent was revoked meanwhile). Final, not an error."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class RecordingJob:
    event_id: str
    tenant_id: str
    encounter_id: str
    recording_id: str
    bucket: str
    object_key: str
    content_type: str
    correlation_id: str

    @staticmethod
    def from_event(envelope: dict) -> RecordingJob | None:
        """Returns None for event types this service does not handle."""
        if envelope.get("eventType") != EVENT_TYPE:
            return None
        if envelope.get("schemaVersion") != 1:
            raise ValueError("unsupported schemaVersion")
        data = envelope["data"]
        return RecordingJob(
            event_id=envelope["eventId"], tenant_id=envelope["tenantId"], encounter_id=data["encounterId"],
            recording_id=data["recordingId"], bucket=data["bucket"], object_key=data["objectKey"],
            content_type=data.get("contentType", "application/octet-stream"),
            correlation_id=envelope.get("correlationId", envelope["eventId"]),
        )


class Storage(Protocol):
    def get(self, bucket: str, key: str) -> bytes: ...


class ModelGateway(Protocol):
    def transcribe(self, *, tenant_id: str, request_id: str, audio: bytes, content_type: str) -> dict: ...

    def generate(self, *, tenant_id: str, request_id: str, segments: list[dict]) -> dict: ...


class Core(Protocol):
    def submit_transcript(self, encounter_id: str, payload: dict, correlation_id: str) -> None: ...

    def submit_draft(self, encounter_id: str, payload: dict, correlation_id: str) -> None: ...

    def report_failure(self, encounter_id: str, payload: dict, correlation_id: str) -> None: ...


def _provenance(p: dict) -> dict:
    return {"executionId": p["execution_id"], "modelId": p["model_id"], "modelVersion": p["model_version"],
            "promptId": p.get("prompt_id"), "promptVersion": p.get("prompt_version"),
            "policyVersion": p["policy_version"], "route": p["route"]}


def ground(sections: list[dict], valid_seqs: set[int]) -> list[dict]:
    """Keeps only evidence that exists in the transcript and marks unsupported statements.

    A statement with no surviving evidence is not dropped: the clinician must see it,
    flagged, and resolve it before sign-off.
    """
    grounded = []
    for section in sections:
        statements = []
        for st in section.get("statements", []):
            evidence = sorted({int(e) for e in st.get("evidence", []) if int(e) in valid_seqs})
            statements.append({"text": st["text"], "evidenceSeqs": evidence, "supported": bool(evidence),
                               "confidence": float(st.get("confidence", 0.0))})
        grounded.append({"key": section["key"], "title": section["title"], "statements": statements})
    return grounded


def process_recording(job: RecordingJob, storage: Storage, gateway: ModelGateway, core: Core) -> str:
    try:
        audio = storage.get(job.bucket, job.object_key)
        tr = gateway.transcribe(tenant_id=job.tenant_id, request_id=f"{job.event_id}:asr", audio=audio,
                                content_type=job.content_type)
        segments = tr["segments"]
        core.submit_transcript(job.encounter_id, {
            "recordingId": job.recording_id, "idempotencyKey": f"{job.event_id}:transcript",
            "segments": [{"seq": s["seq"], "speaker": s["speaker"], "language": s["language"], "text": s["text"],
                          "startMs": s["start_ms"], "endMs": s["end_ms"], "confidence": s["confidence"]}
                         for s in segments],
            "provenance": _provenance(tr["provenance"]),
        }, job.correlation_id)
        gen = gateway.generate(tenant_id=job.tenant_id, request_id=f"{job.event_id}:draft", segments=segments)
        core.submit_draft(job.encounter_id, {
            "idempotencyKey": f"{job.event_id}:draft", "template": "soap",
            "sections": ground(gen["sections"], {s["seq"] for s in segments}),
            "provenance": _provenance(gen["provenance"]),
        }, job.correlation_id)
        return "draft_ready"
    except Rejected:
        return "rejected_by_core"  # core's state is authoritative; nothing to retry or report
    except GatewayDenied as denied:
        core.report_failure(job.encounter_id, {"idempotencyKey": f"{job.event_id}:failed",
                                               "reasonCode": denied.code}, job.correlation_id)
        return "ai_unavailable"


def handle_event(envelope: dict, storage: Storage, gateway: ModelGateway, core: Core, *, attempts: int = 3,
                 sleep=lambda seconds: None) -> str:
    """Bounded retry around one event. After the last attempt the encounter is told that
    AI processing failed, so the clinician gets the manual-note path instead of a silent hang."""
    job = RecordingJob.from_event(envelope)
    if job is None:
        return "ignored"
    for attempt in range(1, attempts + 1):
        try:
            return process_recording(job, storage, gateway, core)
        except Retryable:
            if attempt == attempts:
                break
            sleep(2 ** attempt)
    core.report_failure(job.encounter_id, {"idempotencyKey": f"{job.event_id}:failed",
                                           "reasonCode": "processing_failed"}, job.correlation_id)
    return "failed"
