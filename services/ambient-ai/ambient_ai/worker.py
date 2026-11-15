"""Runtime wiring: Kafka consumer plus a health endpoint. `uvicorn ambient_ai.worker:app`.

Offsets are committed only after an event has been handled, so a crash redelivers it;
core deduplicates by idempotency key. An event that cannot be handled at all is copied
to the dead-letter topic before its offset is committed, so nothing is dropped silently
and one bad event cannot block the ones behind it. Logs carry event ids and error
types only, never clinical content.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI

from .clients import CoreClient, ModelGatewayClient, S3Storage, TokenProvider
from .pipeline import handle_event

log = logging.getLogger("ambient_ai")
RESTART_DELAY_SECONDS = 5


def _env(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"{name} must be set")
    return value


async def handle_message(value: bytes, process, dead_letter) -> str:
    """One message: handled, ignored or dead-lettered. Never raises for a bad event."""
    try:
        envelope = json.loads(value)
    except ValueError:
        envelope = None
    if not isinstance(envelope, dict):
        await dead_letter(value, "unreadable_event")
        return "dead_lettered"
    try:
        return await asyncio.to_thread(process, envelope)
    except Exception as exc:  # noqa: BLE001 - any failure here must end in the dead-letter topic
        log.error("event %s failed with %s", envelope.get("eventId"), type(exc).__name__)
        await dead_letter(value, type(exc).__name__)
        return "dead_lettered"


async def consume(state: dict) -> None:
    from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

    topic = os.environ.get("AFIA_EVENTS_TOPIC", "afia.encounter.events.v1")
    bootstrap = _env("AFIA_KAFKA_BOOTSTRAP")
    model_timeout = float(os.environ.get("AFIA_MODEL_TIMEOUT_SECONDS", "900"))
    token = TokenProvider(_env("AFIA_OIDC_TOKEN_URL"), _env("AFIA_CLIENT_ID"), _env("AFIA_CLIENT_SECRET"))
    gateway = ModelGatewayClient(httpx.Client(base_url=_env("AFIA_MODEL_GATEWAY_URL"), timeout=model_timeout), token)
    core = CoreClient(httpx.Client(base_url=_env("AFIA_CORE_URL"), timeout=30), token)
    storage = S3Storage(_env("AFIA_S3_ENDPOINT"), _env("AFIA_S3_ACCESS_KEY"), _env("AFIA_S3_SECRET_KEY"),
                        os.environ.get("AFIA_S3_REGION", "me-central-1"))
    consumer = AIOKafkaConsumer(topic, bootstrap_servers=bootstrap, group_id="ambient-ai",
                                enable_auto_commit=False, auto_offset_reset="earliest",
                                max_poll_interval_ms=int(model_timeout * 4 * 1000))
    producer = AIOKafkaProducer(bootstrap_servers=bootstrap, acks="all")

    async def dead_letter(value: bytes, reason: str) -> None:
        await producer.send_and_wait(f"{topic}.dlq", value, headers=[("reason", reason.encode())])

    def process(envelope: dict) -> str:
        return handle_event(envelope, storage, gateway, core, sleep=time.sleep)

    await producer.start()
    await consumer.start()
    state["consuming"] = True
    try:
        async for message in consumer:
            outcome = await handle_message(message.value, process, dead_letter)
            log.info("offset %s -> %s", message.offset, outcome)
            await consumer.commit()
    finally:
        state["consuming"] = False
        await consumer.stop()
        await producer.stop()


async def consume_forever(state: dict) -> None:
    """Kafka or the identity provider may not be up yet, or may go away: keep trying."""
    while True:
        try:
            await consume(state)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            log.error("consumer stopped with %s; restarting in %ss", type(exc).__name__, RESTART_DELAY_SECONDS)
            await asyncio.sleep(RESTART_DELAY_SECONDS)


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.status = {"consuming": False}
    task = asyncio.create_task(consume_forever(app.state.status))
    yield
    task.cancel()


app = FastAPI(title="Afia ambient-ai", version="0.1.0", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "UP" if app.state.status["consuming"] else "STARTING"}
