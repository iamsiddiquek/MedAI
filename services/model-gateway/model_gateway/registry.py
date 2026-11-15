"""Registry of models, prompts, routes and tenant policy. Loaded from versioned YAML."""
from __future__ import annotations

import os
import threading
from dataclasses import dataclass, field, replace
from pathlib import Path

import yaml


@dataclass(frozen=True)
class ModelVersion:
    id: str
    provider: str
    kind: str
    version: str
    region: str
    status: str
    use_cases: tuple[str, ...]
    upstream: str = ""  # the model name the router knows this model by


@dataclass(frozen=True)
class PromptVersion:
    id: str
    version: int
    purpose: str
    status: str
    approved_models: tuple[str, ...]
    template: str


@dataclass(frozen=True)
class Route:
    primary: str
    fallbacks: tuple[str, ...] = ()
    prompt: str | None = None


@dataclass(frozen=True)
class TenantPolicy:
    ai_enabled: bool = True
    allowed_regions: tuple[str, ...] = ()
    disabled_use_cases: tuple[str, ...] = ()
    version: int = 1


@dataclass
class Registry:
    policy_version: str
    models: dict[str, ModelVersion]
    prompts: dict[str, PromptVersion]
    routes: dict[str, Route]
    default_tenant_policy: TenantPolicy

    @staticmethod
    def load(path: Path | None = None) -> Registry:
        """Loads `path`, else the file named by AFIA_REGISTRY_PATH, else the synthetic registry."""
        configured = os.environ.get("AFIA_REGISTRY_PATH")
        source = path or (Path(configured) if configured else Path(__file__).with_name("registry.yaml"))
        raw = yaml.safe_load(source.read_text(encoding="utf-8"))
        models = {
            m["id"]: ModelVersion(m["id"], m["provider"], m["kind"], str(m["version"]), m["region"],
                                  m["status"], tuple(m["use_cases"]), str(m.get("upstream", m["id"])))
            for m in raw["models"]
        }
        prompts = {
            p["id"]: PromptVersion(p["id"], int(p["version"]), p["purpose"], p["status"],
                                   tuple(p["approved_models"]), p["template"])
            for p in raw["prompts"]
        }
        routes = {
            name: Route(r["primary"], tuple(r.get("fallbacks", [])), r.get("prompt"))
            for name, r in raw["routes"].items()
        }
        d = raw["tenants"]["default"]
        default = TenantPolicy(bool(d["ai_enabled"]), tuple(d["allowed_regions"]), tuple(d["disabled_use_cases"]))
        return Registry(str(raw["policy_version"]), models, prompts, routes, default)


@dataclass
class PolicyStore:
    """Tenant AI policy and the global kill switch.

    In-memory in the walking skeleton; the interface is what a PostgreSQL-backed store
    will implement in Phase 2 so policy survives a restart.
    """

    default: TenantPolicy
    _tenants: dict[str, TenantPolicy] = field(default_factory=dict)
    _kill_switch: bool = False
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def tenant(self, tenant_id: str) -> TenantPolicy:
        with self._lock:
            return self._tenants.get(tenant_id, self.default)

    def set_tenant(self, tenant_id: str, *, ai_enabled: bool, allowed_regions: list[str],
                   disabled_use_cases: list[str]) -> TenantPolicy:
        with self._lock:
            current = self._tenants.get(tenant_id, self.default)
            updated = replace(current, ai_enabled=ai_enabled, allowed_regions=tuple(allowed_regions),
                              disabled_use_cases=tuple(disabled_use_cases), version=current.version + 1)
            self._tenants[tenant_id] = updated
            return updated

    @property
    def kill_switch(self) -> bool:
        with self._lock:
            return self._kill_switch

    def set_kill_switch(self, engaged: bool) -> None:
        with self._lock:
            self._kill_switch = engaged
