"""Policy enforcement, routing, fallback and provenance for every model call."""
from __future__ import annotations

import hashlib
import json
import threading
import time
import uuid
from collections.abc import Callable
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime

from .providers import ProviderError
from .registry import ModelVersion, PolicyStore, Registry


class PolicyDenied(Exception):
    """The request must not reach any model. Fail closed."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class AllRoutesFailed(Exception):
    pass


@dataclass
class Execution:
    """Ledger row. Holds hashes and versions, never prompt, transcript or audio content."""

    execution_id: str
    tenant_id: str
    use_case: str
    request_id: str
    outcome: str  # completed | denied | failed
    reason: str | None
    model_id: str | None
    model_version: str | None
    provider: str | None
    region: str | None
    prompt_id: str | None
    prompt_version: int | None
    policy_version: str
    tenant_policy_version: int
    route: str | None  # primary | fallback
    attempts: list[dict] = field(default_factory=list)
    input_sha256: str | None = None
    output_sha256: str | None = None
    latency_ms: int = 0
    created_at: str = ""


class Ledger:
    """Append-only execution ledger (in-memory in the walking skeleton)."""

    def __init__(self) -> None:
        self._rows: dict[str, Execution] = {}
        self._lock = threading.Lock()

    def append(self, row: Execution) -> None:
        with self._lock:
            self._rows[row.execution_id] = row

    def get(self, execution_id: str) -> Execution | None:
        with self._lock:
            return self._rows.get(execution_id)

    def all(self) -> list[Execution]:
        with self._lock:
            return list(self._rows.values())


def sha256(value: bytes | object) -> str:
    data = value if isinstance(value, bytes) else json.dumps(value, sort_keys=True, ensure_ascii=False).encode()
    return hashlib.sha256(data).hexdigest()


class Gateway:
    def __init__(self, registry: Registry, policy: PolicyStore, providers: dict[str, object], ledger: Ledger):
        self.registry, self.policy, self.providers, self.ledger = registry, policy, providers, ledger

    def execute(self, *, tenant_id: str, use_case: str, request_id: str, input_hash: str,
                call: Callable[[object, ModelVersion, str | None], object]) -> tuple[object, Execution]:
        started = time.monotonic()
        tenant = self.policy.tenant(tenant_id)
        route = self.registry.routes.get(use_case)
        prompt = self.registry.prompts.get(route.prompt) if route and route.prompt else None
        row = Execution(
            execution_id=str(uuid.uuid4()), tenant_id=tenant_id, use_case=use_case, request_id=request_id,
            outcome="denied", reason=None, model_id=None, model_version=None, provider=None, region=None,
            prompt_id=prompt.id if prompt else None, prompt_version=prompt.version if prompt else None,
            policy_version=self.registry.policy_version, tenant_policy_version=tenant.version, route=None,
            input_sha256=input_hash, created_at=datetime.now(UTC).isoformat(),
        )

        def finish(outcome: str, reason: str | None) -> Execution:
            row.outcome, row.reason = outcome, reason
            row.latency_ms = int((time.monotonic() - started) * 1000)
            self.ledger.append(row)
            return row

        if self.policy.kill_switch:
            raise PolicyDenied(finish("denied", "ai_globally_disabled").reason)
        if not tenant.ai_enabled:
            raise PolicyDenied(finish("denied", "ai_disabled_for_tenant").reason)
        if use_case in tenant.disabled_use_cases:
            raise PolicyDenied(finish("denied", "use_case_disabled_for_tenant").reason)
        if route is None:
            raise PolicyDenied(finish("denied", "unknown_use_case").reason)
        if prompt is not None and prompt.status != "approved":
            raise PolicyDenied(finish("denied", "prompt_not_approved").reason)

        candidates = []
        for position, model_id in enumerate((route.primary, *route.fallbacks)):
            model = self.registry.models.get(model_id)
            if model is None or model.status != "approved" or use_case not in model.use_cases:
                continue
            if prompt is not None and model.id not in prompt.approved_models:
                continue
            if model.region not in tenant.allowed_regions:
                continue  # residency: never route outside the tenant's approved regions
            candidates.append((position, model))
        if not candidates:
            raise PolicyDenied(finish("denied", "no_compliant_route").reason)

        for position, model in candidates:
            provider = self.providers.get(model.provider)
            if provider is None:
                row.attempts.append({"model_id": model.id, "error": "provider_not_configured"})
                continue
            try:
                output = call(provider, model, prompt.template if prompt else None)
            except ProviderError as exc:
                row.attempts.append({"model_id": model.id, "error": type(exc).__name__})
                continue
            row.attempts.append({"model_id": model.id, "error": None})
            row.model_id, row.model_version, row.provider, row.region = model.id, model.version, model.provider, model.region
            row.route = "primary" if position == 0 else "fallback"
            row.output_sha256 = sha256(output)
            return output, finish("completed", None)

        finish("failed", "all_routes_failed")
        raise AllRoutesFailed(row.execution_id)


def provenance(row: Execution) -> dict:
    keep = ("execution_id", "model_id", "model_version", "provider", "region", "prompt_id", "prompt_version",
            "policy_version", "route", "latency_ms")
    return {k: v for k, v in asdict(row).items() if k in keep}
