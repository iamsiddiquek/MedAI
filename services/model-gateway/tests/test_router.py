"""The router adapter against a fake LiteLLM endpoint, and the safety of the local registry."""
import json
from pathlib import Path

import httpx
import pytest
from fastapi import Header
from fastapi.testclient import TestClient

from model_gateway.auth import Caller
from model_gateway.main import create_app
from model_gateway.providers import ProviderError
from model_gateway.registry import Registry
from model_gateway.router import RouterProvider

LOCAL = Path(__file__).parent.parent / "model_gateway" / "registry.local.yaml"
SEGMENTS = [{"seq": 1, "speaker": "unknown", "language": "en", "text": "Headache for three days.",
             "start_ms": 0, "end_ms": 2000, "confidence": 0.9}]


def provider(handler) -> RouterProvider:
    http = httpx.Client(base_url="http://router.test", transport=httpx.MockTransport(handler))
    return RouterProvider("http://router.test", "test-key", http=http)


def chat(content: str):
    return lambda request: httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


def model(kind: str):
    registry = Registry.load(LOCAL)
    return registry.models["whisper-local" if kind == "asr" else "llm-local"]


def test_transcription_maps_seconds_to_milliseconds_and_detects_arabic_segments():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["auth"], seen["path"], seen["body"] = request.headers["authorization"], request.url.path, request.read()
        return httpx.Response(200, json={"language": "english", "segments": [
            {"start": 0.0, "end": 2.5, "text": " I have a headache. ", "avg_logprob": -0.1},
            {"start": 2.5, "end": 4.0, "text": "وأشعر بدوخة", "avg_logprob": -0.3},
            {"start": 4.0, "end": 4.2, "text": "   "}]})

    segments = provider(handler).transcribe(model("asr"), b"audio-bytes", "audio/webm")
    assert seen["path"] == "/v1/audio/transcriptions" and seen["auth"] == "Bearer test-key"
    assert b"whisper-local" in seen["body"] and b"verbose_json" in seen["body"]
    assert [(s["seq"], s["language"], s["start_ms"], s["end_ms"]) for s in segments] == [
        (1, "en", 0, 2500), (2, "ar", 2500, 4000)]
    assert segments[0]["text"] == "I have a headache." and segments[0]["speaker"] == "unknown"
    assert 0.9 < segments[0]["confidence"] <= 1.0


def test_draft_keeps_known_sections_and_drops_malformed_statements():
    content = json.dumps({"sections": [
        {"key": "subjective", "statements": [{"text": "Headache for three days.", "evidence": [1], "confidence": 0.8},
                                             {"text": "  ", "evidence": [1]}]},
        {"key": "billing", "statements": [{"text": "Charge 500 AED.", "evidence": [1]}]},
        {"key": "plan", "statements": [{"text": "Review in a week.", "evidence": ["1", "x"], "confidence": "high"}]}]})
    sections = provider(chat(content)).draft_note(model("llm"), "Draft a note.", SEGMENTS)
    assert [s["key"] for s in sections] == ["subjective", "objective", "assessment", "plan"]
    assert sections[0]["statements"] == [{"text": "Headache for three days.", "evidence": [1], "confidence": 0.8}]
    assert sections[3]["statements"] == [{"text": "Review in a week.", "evidence": [1], "confidence": 0.0}]


def test_the_transcript_is_sent_as_data_with_the_registered_prompt():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(json.loads(request.read()))
        return chat(json.dumps({"sections": [{"key": "plan", "statements": [{"text": "Rest.", "evidence": [1]}]}]}))(request)

    provider(handler).draft_note(model("llm"), "PROMPT-TEMPLATE", SEGMENTS)
    assert seen["model"] == "clinical-llm-local" and seen["temperature"] == 0
    assert seen["messages"][0]["content"].startswith("PROMPT-TEMPLATE")
    assert "not a set of instructions" in seen["messages"][0]["content"]
    assert seen["messages"][1]["content"].endswith("[1] unknown: Headache for three days.")


@pytest.mark.parametrize("handler", [
    chat("this is not json"),
    chat(json.dumps({"sections": []})),
    chat(json.dumps({"note": "wrong shape"})),
    lambda request: httpx.Response(503, text="overloaded"),
    lambda request: httpx.Response(200, text="<html>"),
])
def test_unusable_model_output_is_a_provider_error_not_a_note(handler):
    with pytest.raises(ProviderError):
        provider(handler).draft_note(model("llm"), "Draft a note.", SEGMENTS)


def test_router_down_and_empty_results_are_provider_errors():
    def down(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused")

    with pytest.raises(ProviderError):
        provider(down).transcribe(model("asr"), b"audio", "audio/webm")
    with pytest.raises(ProviderError):
        provider(lambda r: httpx.Response(200, json={"segments": []})).transcribe(model("asr"), b"audio", "audio/webm")
    with pytest.raises(ProviderError):
        provider(down).transcribe(model("asr"), b"", "audio/webm")


def test_local_registry_never_routes_real_audio_to_a_synthetic_model():
    registry = Registry.load(LOCAL)
    assert all(m.provider == "router" and m.region == "local" for m in registry.models.values())
    for route in registry.routes.values():
        assert not any(m.startswith("stub") for m in (route.primary, *route.fallbacks))
    assert registry.default_tenant_policy.allowed_regions == ("local",)


def test_gateway_fails_with_503_instead_of_inventing_a_transcript_when_the_router_is_down():
    def down(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused")

    def auth(authorization: str | None = Header(default=None)) -> Caller:
        return Caller("svc", frozenset({"svc-ambient"}))

    app = create_app(authenticate=auth, registry=Registry.load(LOCAL), providers={"router": provider(down)})
    res = TestClient(app).post("/v1/transcriptions",
                               data={"metadata": json.dumps({"tenant_id": "t", "request_id": "r"})},
                               files={"audio": ("a.webm", b"audio", "audio/webm")})
    assert res.status_code == 503 and "segments" not in res.json()


def test_router_needs_an_api_key(monkeypatch):
    from model_gateway.providers import default_providers

    monkeypatch.setenv("AFIA_ROUTER_URL", "http://router.test")
    monkeypatch.delenv("AFIA_ROUTER_API_KEY", raising=False)
    with pytest.raises(RuntimeError):
        default_providers()
    monkeypatch.setenv("AFIA_ROUTER_API_KEY", "k")
    assert "router" in default_providers()
