"""Provider adapters. Only this module may talk to a model, a router or a vendor SDK.

The walking skeleton ships deterministic stubs so the whole flow runs offline and is
testable. A real adapter implements the same two protocols and is registered by name.
"""
from __future__ import annotations

import os
from typing import Protocol

from .registry import ModelVersion


class ProviderError(RuntimeError):
    """The provider could not serve the request; the gateway may try a fallback."""


class AsrProvider(Protocol):
    def transcribe(self, model: ModelVersion, audio: bytes, content_type: str) -> list[dict]: ...


class LlmProvider(Protocol):
    def draft_note(self, model: ModelVersion, prompt: str, segments: list[dict]) -> list[dict]: ...


# Synthetic consultation used by the stub ASR. Not real patient data.
_SCRIPT: list[tuple[str, str, str]] = [
    ("clinician", "en", "Good morning. What brings you in today?"),
    ("patient", "en", "I have had a headache for three days, mostly on the right side."),
    ("patient", "ar", "وأشعر بدوخة في الصباح."),
    ("clinician", "en", "Any nausea, vomiting or changes in your vision?"),
    ("patient", "en", "Some nausea, no vomiting, and my vision is fine."),
    ("patient", "en", "I took paracetamol twice a day but it only helps a little."),
    ("clinician", "en", "Your blood pressure is 128 over 82 and your temperature is 36.8."),
    ("clinician", "en", "The neurological exam is normal, with no neck stiffness."),
    ("clinician", "en", "This is most likely a tension-type headache."),
    ("clinician", "en", "Take ibuprofen 400 milligrams after food, up to three times a day, for five days."),
    ("clinician", "en", "Come back in one week, or sooner if the headache gets worse."),
]


class StubAsr:
    """Deterministic transcript. The audio is not interpreted; an empty file is rejected."""

    def transcribe(self, model: ModelVersion, audio: bytes, content_type: str) -> list[dict]:
        if not audio:
            raise ProviderError("empty audio")
        segments, cursor = [], 0
        for seq, (speaker, language, text) in enumerate(_SCRIPT, start=1):
            duration = 900 + 70 * len(text)
            segments.append({"seq": seq, "speaker": speaker, "language": language, "text": text,
                             "start_ms": cursor, "end_ms": cursor + duration, "confidence": 0.93})
            cursor += duration + 250
        return segments


_OBJECTIVE = ("blood pressure", "temperature", "exam", "pulse", "weight")
_ASSESSMENT = ("most likely", "diagnosis", "consistent with")
_PLAN = ("take ", "come back", "follow up", "prescribe", "refer")


class StubLlm:
    """Rule-based SOAP drafting that cites the segment each statement came from.

    It also emits one statement with no evidence. That is deliberate: it exercises the
    grounding check and the review rule that an unsupported statement blocks sign-off.
    """

    def draft_note(self, model: ModelVersion, prompt: str, segments: list[dict]) -> list[dict]:
        if not segments:
            raise ProviderError("no transcript segments")
        buckets: dict[str, list[dict]] = {"subjective": [], "objective": [], "assessment": [], "plan": []}
        for seg in segments:
            text, lowered = seg["text"], seg["text"].lower()
            if text.rstrip().endswith("?"):
                continue
            if seg["speaker"] == "patient":
                key = "subjective"
            elif any(w in lowered for w in _OBJECTIVE):
                key = "objective"
            elif any(w in lowered for w in _ASSESSMENT):
                key = "assessment"
            elif any(w in lowered for w in _PLAN):
                key = "plan"
            else:
                continue
            buckets[key].append({"text": text, "evidence": [seg["seq"]], "confidence": 0.9})
        buckets["subjective"].append({"text": "No known drug allergies.", "evidence": [], "confidence": 0.4})
        titles = {"subjective": "Subjective", "objective": "Objective", "assessment": "Assessment", "plan": "Plan"}
        return [{"key": k, "title": titles[k], "statements": v} for k, v in buckets.items()]


def default_providers() -> dict[str, object]:
    """Synthetic providers always exist for tests; the router is added when it is configured.

    Which provider serves a use case is decided by the registry, never here: the local
    registry lists no synthetic model, so real audio can never receive a synthetic result.
    """
    providers: dict[str, object] = {"stub-asr": StubAsr(), "stub-llm": StubLlm()}
    router_url = os.environ.get("AFIA_ROUTER_URL")
    if router_url:
        from .router import RouterProvider

        api_key = os.environ.get("AFIA_ROUTER_API_KEY")
        if not api_key:
            raise RuntimeError("AFIA_ROUTER_API_KEY must be set when AFIA_ROUTER_URL is set")
        providers["router"] = RouterProvider(router_url, api_key)
    return providers
