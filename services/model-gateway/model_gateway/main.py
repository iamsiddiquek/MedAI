"""HTTP API of the model-gateway."""
from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import asdict

from fastapi import Depends, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from .auth import Caller, authenticator_from_env
from .gateway import AllRoutesFailed, Gateway, Ledger, PolicyDenied, provenance, sha256
from .providers import default_providers
from .registry import PolicyStore, Registry

CALLER_ROLE = "svc-ambient"
ADMIN_ROLE = "platform-operator"
MAX_AUDIO_BYTES = 200 * 1024 * 1024


class Segment(BaseModel):
    seq: int
    speaker: str
    language: str
    text: str
    start_ms: int
    end_ms: int
    confidence: float = 1.0


class GenerationRequest(BaseModel):
    tenant_id: str = Field(min_length=1)
    request_id: str = Field(min_length=1)
    use_case: str = "clinical_note_draft"
    segments: list[Segment]


class TenantPolicyBody(BaseModel):
    ai_enabled: bool
    allowed_regions: list[str]
    disabled_use_cases: list[str] = []


class KillSwitchBody(BaseModel):
    engaged: bool


def create_app(*, authenticate: Callable[..., Caller] | None = None, registry: Registry | None = None,
               providers: dict[str, object] | None = None) -> FastAPI:
    registry = registry or Registry.load()
    policy = PolicyStore(default=registry.default_tenant_policy)
    ledger = Ledger()
    gateway = Gateway(registry, policy, providers or default_providers(), ledger)
    authenticate = authenticate or authenticator_from_env()

    app = FastAPI(title="Afia model-gateway", version="0.1.0")
    app.state.gateway, app.state.policy, app.state.ledger = gateway, policy, ledger

    @app.exception_handler(PolicyDenied)
    async def denied(_, exc: PolicyDenied):
        return JSONResponse(status_code=403, content={"code": exc.code})

    @app.exception_handler(AllRoutesFailed)
    async def failed(_, exc: AllRoutesFailed):
        return JSONResponse(status_code=503, content={"code": "all_routes_failed", "execution_id": str(exc)})

    @app.get("/health")
    def health():
        return {"status": "UP", "policy_version": registry.policy_version}

    @app.post("/v1/transcriptions")
    async def transcribe(metadata: str = Form(...), audio: UploadFile = File(...),
                         caller: Caller = Depends(authenticate)):
        caller.require(CALLER_ROLE)
        try:
            meta = json.loads(metadata)
            tenant_id, request_id = str(meta["tenant_id"]), str(meta["request_id"])
        except (ValueError, KeyError, TypeError) as exc:
            raise HTTPException(status_code=422, detail={"code": "invalid_metadata"}) from exc
        data = await audio.read(MAX_AUDIO_BYTES + 1)
        if len(data) > MAX_AUDIO_BYTES:
            raise HTTPException(status_code=413, detail={"code": "audio_too_large"})
        content_type = audio.content_type or "application/octet-stream"
        segments, row = gateway.execute(
            tenant_id=tenant_id, use_case=meta.get("use_case", "ambient_transcription"), request_id=request_id,
            input_hash=sha256(data),
            call=lambda provider, model, _prompt: provider.transcribe(model, data, content_type),
        )
        return {"segments": segments, "provenance": provenance(row)}

    @app.post("/v1/generations")
    def generate(body: GenerationRequest, caller: Caller = Depends(authenticate)):
        caller.require(CALLER_ROLE)
        segments = [s.model_dump() for s in body.segments]
        sections, row = gateway.execute(
            tenant_id=body.tenant_id, use_case=body.use_case, request_id=body.request_id,
            input_hash=sha256(segments),
            call=lambda provider, model, prompt: provider.draft_note(model, prompt or "", segments),
        )
        return {"sections": sections, "provenance": provenance(row)}

    @app.get("/v1/executions/{execution_id}")
    def execution(execution_id: str, caller: Caller = Depends(authenticate)):
        caller.require(ADMIN_ROLE)
        row = ledger.get(execution_id)
        if row is None:
            raise HTTPException(status_code=404, detail={"code": "not_found"})
        return asdict(row)

    @app.put("/v1/admin/tenants/{tenant_id}/policy")
    def set_policy(tenant_id: str, body: TenantPolicyBody, caller: Caller = Depends(authenticate)):
        caller.require(ADMIN_ROLE)
        return asdict(policy.set_tenant(tenant_id, ai_enabled=body.ai_enabled,
                                        allowed_regions=body.allowed_regions,
                                        disabled_use_cases=body.disabled_use_cases))

    @app.put("/v1/admin/kill-switch")
    def kill_switch(body: KillSwitchBody, caller: Caller = Depends(authenticate)):
        caller.require(ADMIN_ROLE)
        policy.set_kill_switch(body.engaged)
        return {"engaged": policy.kill_switch}

    return app
