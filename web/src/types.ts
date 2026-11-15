export type EncounterState =
  | "CREATED" | "CONSENT_PENDING" | "READY" | "RECORDING" | "PROCESSING" | "REVIEW" | "SIGNED" | "EXPORTED" | "CLOSED";

export interface Me { subject: string; name: string; tenantId: string; roles: string[] }

export interface Patient {
  id: string; mrn: string; mrnIssuer: string; fullName: string; dateOfBirth: string; sex: string | null;
}

export interface Encounter {
  id: string; patientId: string; clinicianId: string; clinicianName: string; state: EncounterState;
  aiStatus: "OK" | "UNAVAILABLE"; aiStatusReason: string | null; version: number; createdAt: string;
}

export interface Consent {
  decision: "GRANTED" | "REFUSED" | "REVOKED"; consentTextVersion: string; language: string;
  recordedBy: string; recordedAt: string;
}

export interface EncounterView { encounter: Encounter; patient: Patient | null; consent: Consent | null }

export interface Provenance {
  executionId: string | null; modelId: string | null; modelVersion: string | null; promptId: string | null;
  promptVersion: number | null; policyVersion: string | null; route: string | null;
}

export interface Segment {
  seq: number; speaker: string; language: string; text: string; startMs: number; endMs: number; confidence: number;
}

export interface Transcript { id: string; segments: Segment[]; provenance: Provenance; createdAt: string }

export type StatementStatus = "PENDING" | "ACCEPTED" | "EDITED" | "REJECTED";

export interface Statement {
  ord: number; text: string; origin: "AI" | "CLINICIAN"; supported: boolean; confidence: number;
  status: StatementStatus; evidenceSeqs: number[];
}

export interface Section { key: string; title: string; statements: Statement[] }

export interface Note {
  id: string; state: "AI_GENERATED" | "UNDER_REVIEW" | "SIGNED"; origin: "AI" | "MANUAL"; template: string;
  version: number; provenance: Provenance; signedBy: string | null; signedByName: string | null;
  signedAt: string | null; signedVersion: number | null; sections: Section[];
}

export interface NoteVersion { version: number; author: string; authorKind: "AI" | "CLINICIAN"; summary: string; createdAt: string }

export interface Recording { id: string; state: string }

export interface AuditEvent {
  seq: number; actor: string; action: string; resourceType: string; resourceId: string; outcome: string;
  correlationId: string; occurredAt: string; hash: string;
}

export interface AuditVerification { valid: boolean; events: number; firstBrokenSeq: number | null }
