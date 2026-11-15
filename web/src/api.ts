import { config } from "./config";
import type {
  AuditEvent, AuditVerification, EncounterView, Me, Note, NoteVersion, Patient, Recording, Transcript,
} from "./types";

/** A refusal from the API. `code` is a stable identifier; it never contains clinical content. */
export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(code);
  }
}

type TokenSource = () => string | null;
let tokenSource: TokenSource = () => null;
let refreshToken: () => Promise<boolean> = async () => false;
let onUnauthenticated: () => void = () => undefined;

export function configureApi(source: TokenSource, refresh: () => Promise<boolean>, unauthenticated: () => void) {
  tokenSource = source;
  refreshToken = refresh;
  onUnauthenticated = unauthenticated;
}

async function request<T>(method: string, path: string, body?: unknown, audio?: Blob): Promise<T> {
  const send = async (): Promise<Response> => {
    const token = tokenSource();
    const headers: Record<string, string> = { "X-Correlation-Id": crypto.randomUUID() };
    if (token) headers.Authorization = `Bearer ${token}`;
    let payload: BodyInit | undefined;
    if (audio) {
      headers["Content-Type"] = audio.type || "audio/webm";
      payload = audio;
    } else if (body !== undefined) {
      headers["Content-Type"] = "application/json";
      payload = JSON.stringify(body);
    }
    try {
      return await fetch(config.apiBase + path, { method, headers, body: payload });
    } catch {
      throw new ApiError(0, "network_unavailable");
    }
  };
  let response = await send();
  // An expired token is renewed once, quietly, and the same request is sent again. This matters
  // most for the audio upload at the end of a long recording.
  if (response.status === 401 && (await refreshToken())) response = await send();
  if (response.status === 401) {
    onUnauthenticated();
    throw new ApiError(401, "unauthenticated");
  }
  if (!response.ok) {
    let code = `http_${response.status}`;
    try {
      const parsed: unknown = await response.json();
      if (parsed && typeof parsed === "object" && "code" in parsed && typeof parsed.code === "string") code = parsed.code;
    } catch {
      // the body was not JSON; keep the status-based code
    }
    throw new ApiError(response.status, code);
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

const enc = (id: string) => `/api/encounters/${encodeURIComponent(id)}`;

export const api = {
  me: () => request<Me>("GET", "/api/me"),
  searchPatients: (query: string) => request<Patient[]>("GET", `/api/patients?query=${encodeURIComponent(query)}`),
  createPatient: (patient: Omit<Patient, "id">) => request<Patient>("POST", "/api/patients", patient),
  encounters: () => request<EncounterView[]>("GET", "/api/encounters"),
  encounter: (id: string) => request<EncounterView>("GET", enc(id)),
  createEncounter: (patientId: string) => request<EncounterView>("POST", "/api/encounters", { patientId }),
  recordConsent: (id: string, decision: "GRANTED" | "REFUSED", language: string) =>
    request<EncounterView>("POST", `${enc(id)}/consent`, {
      decision, language, consentTextVersion: config.consentTextVersion,
    }),
  revokeConsent: (id: string) => request<EncounterView>("POST", `${enc(id)}/consent/revoke`),
  startRecording: (id: string) => request<Recording>("POST", `${enc(id)}/recordings`, { patientConfirmed: true }),
  activeRecording: (id: string) => request<Recording>("GET", `${enc(id)}/recordings/active`),
  uploadAudio: (id: string, recordingId: string, audio: Blob) =>
    request<Recording>("PUT", `${enc(id)}/recordings/${encodeURIComponent(recordingId)}/audio`, undefined, audio),
  transcript: (id: string) => request<Transcript>("GET", `${enc(id)}/transcript`),
  note: (id: string) => request<Note>("GET", `${enc(id)}/note`),
  noteVersions: (id: string) => request<NoteVersion[]>("GET", `${enc(id)}/note/versions`),
  startManualNote: (id: string) => request<Note>("POST", `${enc(id)}/note/manual`),
  reviewStatement: (id: string, sectionKey: string, ord: number, action: "ACCEPT" | "EDIT" | "REJECT",
                    expectedVersion: number, text?: string) =>
    request<Note>("POST", `${enc(id)}/note/statements/review`, { sectionKey, ord, action, text, expectedVersion }),
  addStatement: (id: string, sectionKey: string, text: string, expectedVersion: number) =>
    request<Note>("POST", `${enc(id)}/note/statements`, { sectionKey, text, expectedVersion }),
  sign: (id: string, expectedVersion: number) =>
    request<Note>("POST", `${enc(id)}/note/sign`, { attested: true, expectedVersion }),
  auditEvents: (resourceId: string) =>
    request<AuditEvent[]>("GET", `/api/audit/events?resourceId=${encodeURIComponent(resourceId)}`),
  auditVerify: () => request<AuditVerification>("GET", "/api/audit/verify"),
};
