#!/usr/bin/env sh
# Creates .env with random local secrets. Refuses to overwrite an existing file.
set -eu
cd "$(dirname "$0")/../.."
[ -e .env ] && { echo ".env already exists; not overwriting" >&2; exit 1; }
rand() { openssl rand -hex "$1"; }
umask 077
cat > .env <<ENV
AFIA_DB_PASSWORD=$(rand 16)
KEYCLOAK_ADMIN_PASSWORD=$(rand 16)
KEYCLOAK_DB_PASSWORD=$(rand 16)
AFIA_S3_ACCESS_KEY=afia$(rand 6)
AFIA_S3_SECRET_KEY=$(rand 20)
AFIA_AMBIENT_CLIENT_SECRET=$(rand 24)
LITELLM_MASTER_KEY=sk-$(rand 24)
AFIA_DEV_USER_PASSWORD=$(rand 8)
AFIA_WHISPER_MODEL=Systran/faster-whisper-large-v3
AFIA_OLLAMA_MODEL=qwen2.5:7b
ENV
echo "Wrote .env. Demo user password: $(grep AFIA_DEV_USER_PASSWORD .env | cut -d= -f2)"
