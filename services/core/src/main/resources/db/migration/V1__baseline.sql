-- Afia core baseline. One schema per module; a module writes only its own schema.
-- Cross-module references are plain ids (no foreign keys across schemas) so a module
-- can later be extracted into its own service and database.

CREATE SCHEMA patient;
CREATE SCHEMA encounter;
CREATE SCHEMA media;
CREATE SCHEMA transcript;
CREATE SCHEMA note;
CREATE SCHEMA audit;
CREATE SCHEMA platform;

-- ---------------------------------------------------------------- patient
CREATE TABLE patient.patient (
    id            uuid PRIMARY KEY,
    tenant_id     text NOT NULL,
    mrn           text NOT NULL,
    mrn_issuer    text NOT NULL,
    full_name     text NOT NULL,
    date_of_birth date NOT NULL,
    sex           text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, mrn_issuer, mrn)
);
CREATE INDEX patient_tenant_name_idx ON patient.patient (tenant_id, lower(full_name));

-- -------------------------------------------------------------- encounter
CREATE TABLE encounter.encounter (
    id               uuid PRIMARY KEY,
    tenant_id        text NOT NULL,
    patient_id       uuid NOT NULL,
    clinician_id     text NOT NULL,
    clinician_name   text NOT NULL,
    state            text NOT NULL CHECK (state IN
        ('CREATED','CONSENT_PENDING','READY','RECORDING','PROCESSING','REVIEW','SIGNED','EXPORTED','CLOSED')),
    ai_status        text NOT NULL DEFAULT 'OK' CHECK (ai_status IN ('OK','UNAVAILABLE')),
    ai_status_reason text,
    version          integer NOT NULL DEFAULT 1,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX encounter_tenant_created_idx ON encounter.encounter (tenant_id, created_at DESC);

CREATE TABLE encounter.consent (
    id                   uuid PRIMARY KEY,
    tenant_id            text NOT NULL,
    encounter_id         uuid NOT NULL REFERENCES encounter.encounter (id),
    decision             text NOT NULL CHECK (decision IN ('GRANTED','REFUSED','REVOKED')),
    consent_text_version text NOT NULL,
    language             text NOT NULL,
    recorded_by          text NOT NULL,
    recorded_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX consent_encounter_idx ON encounter.consent (encounter_id, recorded_at DESC);

-- ------------------------------------------------------------------ media
CREATE TABLE media.recording (
    id           uuid PRIMARY KEY,
    tenant_id    text NOT NULL,
    encounter_id uuid NOT NULL,
    state        text NOT NULL CHECK (state IN ('STARTED','UPLOADED','QUARANTINED')),
    bucket       text,
    object_key   text,
    content_type text,
    size_bytes   bigint,
    sha256       text,
    started_by   text NOT NULL,
    started_at   timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
-- At most one active recording per encounter.
CREATE UNIQUE INDEX recording_one_active_idx ON media.recording (encounter_id) WHERE state = 'STARTED';

-- ------------------------------------------------------------- transcript
CREATE TABLE transcript.transcript (
    id              uuid PRIMARY KEY,
    tenant_id       text NOT NULL,
    encounter_id    uuid NOT NULL UNIQUE,
    recording_id    uuid NOT NULL,
    idempotency_key text NOT NULL UNIQUE,
    execution_id    text NOT NULL,
    model_id        text NOT NULL,
    model_version   text NOT NULL,
    policy_version  text NOT NULL,
    route           text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE transcript.segment (
    transcript_id uuid NOT NULL REFERENCES transcript.transcript (id),
    seq           integer NOT NULL,
    speaker       text NOT NULL,
    language      text NOT NULL,
    text          text NOT NULL,
    start_ms      integer NOT NULL,
    end_ms        integer NOT NULL,
    confidence    numeric(4,3) NOT NULL,
    PRIMARY KEY (transcript_id, seq)
);

-- ------------------------------------------------------------------- note
CREATE TABLE note.note (
    id              uuid PRIMARY KEY,
    tenant_id       text NOT NULL,
    encounter_id    uuid NOT NULL UNIQUE,
    state           text NOT NULL CHECK (state IN ('AI_GENERATED','UNDER_REVIEW','SIGNED')),
    origin          text NOT NULL CHECK (origin IN ('AI','MANUAL')),
    template        text NOT NULL,
    current_version integer NOT NULL,
    idempotency_key text UNIQUE,
    execution_id    text,
    model_id        text,
    model_version   text,
    prompt_id       text,
    prompt_version  integer,
    policy_version  text,
    route           text,
    signed_by       text,
    signed_by_name  text,
    signed_at       timestamptz,
    signed_version  integer,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE note.note_version (
    note_id     uuid NOT NULL REFERENCES note.note (id),
    version     integer NOT NULL,
    author      text NOT NULL,
    author_kind text NOT NULL CHECK (author_kind IN ('AI','CLINICIAN')),
    summary     text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (note_id, version)
);
CREATE TABLE note.statement (
    note_id       uuid NOT NULL,
    version       integer NOT NULL,
    section_key   text NOT NULL,
    ord           integer NOT NULL,
    text          text NOT NULL,
    origin        text NOT NULL CHECK (origin IN ('AI','CLINICIAN')),
    supported     boolean NOT NULL,
    confidence    numeric(4,3) NOT NULL,
    status        text NOT NULL CHECK (status IN ('PENDING','ACCEPTED','EDITED','REJECTED')),
    evidence_seqs integer[] NOT NULL DEFAULT '{}',
    PRIMARY KEY (note_id, version, section_key, ord),
    FOREIGN KEY (note_id, version) REFERENCES note.note_version (note_id, version)
);

-- A signed note and every version of it are immutable; corrections are amendments.
CREATE FUNCTION note.reject_change_when_signed() RETURNS trigger AS $$
DECLARE
    target uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        target := OLD.note_id;
    ELSE
        target := NEW.note_id;
    END IF;
    IF EXISTS (SELECT 1 FROM note.note n WHERE n.id = target AND n.state = 'SIGNED') THEN
        RAISE EXCEPTION 'signed note is immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER statement_immutable_when_signed BEFORE INSERT OR UPDATE OR DELETE ON note.statement
    FOR EACH ROW EXECUTE FUNCTION note.reject_change_when_signed();

-- ------------------------------------------------------------------ audit
CREATE TABLE audit.audit_event (
    seq            bigserial PRIMARY KEY,
    tenant_id      text NOT NULL,
    actor          text NOT NULL,
    action         text NOT NULL,
    resource_type  text NOT NULL,
    resource_id    text NOT NULL,
    outcome        text NOT NULL,
    correlation_id text NOT NULL,
    occurred_at_us bigint NOT NULL,
    prev_hash      text NOT NULL,
    hash           text NOT NULL
);
CREATE INDEX audit_tenant_seq_idx ON audit.audit_event (tenant_id, seq);
CREATE INDEX audit_resource_idx ON audit.audit_event (tenant_id, resource_id);

CREATE FUNCTION audit.reject_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit ledger is append-only';
END $$ LANGUAGE plpgsql;
CREATE TRIGGER audit_event_append_only BEFORE UPDATE OR DELETE ON audit.audit_event
    FOR EACH ROW EXECUTE FUNCTION audit.reject_change();

-- --------------------------------------------------------------- platform
CREATE TABLE platform.outbox (
    id                uuid PRIMARY KEY,
    tenant_id         text NOT NULL,
    aggregate_type    text NOT NULL,
    aggregate_id      text NOT NULL,
    aggregate_version integer NOT NULL,
    event_type        text NOT NULL,
    payload           text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    published_at      timestamptz,
    attempts          integer NOT NULL DEFAULT 0,
    last_error        text
);
CREATE INDEX outbox_unpublished_idx ON platform.outbox (created_at) WHERE published_at IS NULL;
