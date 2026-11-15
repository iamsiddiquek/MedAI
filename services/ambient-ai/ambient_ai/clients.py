"""Outbound adapters: model-gateway, core, object storage and the service token."""
from __future__ import annotations

import json
import time

import httpx

from .pipeline import GatewayDenied, Rejected, Retryable


class TokenProvider:
    """OAuth2 client-credentials token for service-to-service calls, cached until near expiry."""

    def __init__(self, token_url: str, client_id: str, client_secret: str, http: httpx.Client | None = None):
        self._url, self._id, self._secret = token_url, client_id, client_secret
        self._http = http or httpx.Client(timeout=10)
        self._token, self._expires = "", 0.0

    def __call__(self) -> str:
        if time.time() >= self._expires:
            try:
                res = self._http.post(self._url, data={"grant_type": "client_credentials",
                                                       "client_id": self._id, "client_secret": self._secret})
                res.raise_for_status()
            except httpx.HTTPError as exc:
                raise Retryable("identity provider unavailable") from exc
            body = res.json()
            self._token = body["access_token"]
            self._expires = time.time() + max(int(body.get("expires_in", 60)) - 20, 5)
        return self._token


class ModelGatewayClient:
    def __init__(self, http: httpx.Client, token=lambda: ""):
        self._http, self._token = http, token

    def _headers(self) -> dict:
        return {"Authorization": f"Bearer {self._token()}"}

    @staticmethod
    def _check(res: httpx.Response) -> dict:
        if res.status_code == 403:
            raise GatewayDenied(res.json().get("code", "denied"))
        if res.status_code >= 500 or res.status_code in (401, 408, 429):
            raise Retryable(f"model-gateway {res.status_code}")
        res.raise_for_status()
        return res.json()

    def transcribe(self, *, tenant_id: str, request_id: str, audio: bytes, content_type: str) -> dict:
        try:
            res = self._http.post(
                "/v1/transcriptions", headers=self._headers(),
                data={"metadata": json.dumps({"tenant_id": tenant_id, "request_id": request_id})},
                files={"audio": ("audio", audio, content_type)})
        except httpx.TransportError as exc:
            raise Retryable("model-gateway unreachable") from exc
        return self._check(res)

    def generate(self, *, tenant_id: str, request_id: str, segments: list[dict]) -> dict:
        try:
            res = self._http.post("/v1/generations", headers=self._headers(),
                                  json={"tenant_id": tenant_id, "request_id": request_id, "segments": segments})
        except httpx.TransportError as exc:
            raise Retryable("model-gateway unreachable") from exc
        return self._check(res)


class CoreClient:
    def __init__(self, http: httpx.Client, token=lambda: ""):
        self._http, self._token = http, token

    def _send(self, method: str, path: str, payload: dict, correlation_id: str) -> None:
        headers = {"Authorization": f"Bearer {self._token()}", "X-Correlation-Id": correlation_id}
        try:
            res = self._http.request(method, path, json=payload, headers=headers)
        except httpx.TransportError as exc:
            raise Retryable("core unreachable") from exc
        if res.status_code >= 500 or res.status_code in (401, 408, 429):
            raise Retryable(f"core {res.status_code}")
        if res.status_code == 409:
            try:
                code = str(res.json().get("code", "conflict"))
            except ValueError:
                code = "conflict"
            raise Rejected(code)
        res.raise_for_status()

    def submit_transcript(self, encounter_id: str, payload: dict, correlation_id: str) -> None:
        self._send("PUT", f"/internal/encounters/{encounter_id}/transcript", payload, correlation_id)

    def submit_draft(self, encounter_id: str, payload: dict, correlation_id: str) -> None:
        self._send("PUT", f"/internal/encounters/{encounter_id}/draft", payload, correlation_id)

    def report_failure(self, encounter_id: str, payload: dict, correlation_id: str) -> None:
        self._send("POST", f"/internal/encounters/{encounter_id}/processing-failed", payload, correlation_id)


class S3Storage:
    def __init__(self, endpoint_url: str, access_key: str, secret_key: str, region: str):
        import boto3
        from botocore.config import Config

        self._s3 = boto3.client("s3", endpoint_url=endpoint_url, aws_access_key_id=access_key,
                                aws_secret_access_key=secret_key, region_name=region,
                                config=Config(s3={"addressing_style": "path"}, retries={"max_attempts": 3}))

    def get(self, bucket: str, key: str) -> bytes:
        from botocore.exceptions import BotoCoreError, ClientError

        try:
            return self._s3.get_object(Bucket=bucket, Key=key)["Body"].read()
        except (BotoCoreError, ClientError) as exc:
            raise Retryable("object storage unavailable") from exc
