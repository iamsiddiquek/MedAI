"""Caller authentication. Service tokens are verified against the identity provider's JWKS."""
from __future__ import annotations

import os
from dataclasses import dataclass

import jwt
from fastapi import Header, HTTPException


@dataclass(frozen=True)
class Caller:
    subject: str
    roles: frozenset[str]

    def require(self, role: str) -> None:
        if role not in self.roles:
            raise HTTPException(status_code=403, detail={"code": "forbidden"})


class JwtAuthenticator:
    def __init__(self, jwks_url: str, issuer: str):
        self._client = jwt.PyJWKClient(jwks_url)
        self._issuer = issuer

    def __call__(self, authorization: str | None = Header(default=None)) -> Caller:
        if not authorization or not authorization.lower().startswith("bearer "):
            raise HTTPException(status_code=401, detail={"code": "unauthenticated"})
        token = authorization.split(" ", 1)[1]
        try:
            key = self._client.get_signing_key_from_jwt(token).key
            claims = jwt.decode(token, key, algorithms=["RS256"], issuer=self._issuer,
                                options={"verify_aud": False})
        except jwt.PyJWTError as exc:
            raise HTTPException(status_code=401, detail={"code": "invalid_token"}) from exc
        roles = frozenset(claims.get("realm_access", {}).get("roles", []))
        return Caller(subject=str(claims.get("sub", "")), roles=roles)


def authenticator_from_env() -> JwtAuthenticator:
    """There is no unauthenticated mode: missing configuration stops the service at start-up."""
    jwks_url, issuer = os.environ.get("AFIA_OIDC_JWKS_URL"), os.environ.get("AFIA_OIDC_ISSUER")
    if not jwks_url or not issuer:
        raise RuntimeError("AFIA_OIDC_JWKS_URL and AFIA_OIDC_ISSUER must be set")
    return JwtAuthenticator(jwks_url, issuer)
