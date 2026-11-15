import type { Note, Statement } from "./types";

/** Statements the clinician must still resolve: flagged as not supported and not yet edited or rejected. */
export function unresolved(note: Note): Statement[] {
  return note.sections.flatMap((s) => s.statements).filter((st) => !st.supported && st.status === "PENDING");
}

export function keptStatements(note: Note): number {
  return note.sections.flatMap((s) => s.statements).filter((st) => st.status !== "REJECTED").length;
}

/** Mirrors the server's rule. The server decides; this only explains the disabled button. */
export function signBlocker(note: Note, attested: boolean): "signed" | "unresolved" | "empty" | "attest" | null {
  if (note.state === "SIGNED") return "signed";
  if (unresolved(note).length > 0) return "unresolved";
  if (keptStatements(note) === 0) return "empty";
  if (!attested) return "attest";
  return null;
}

/** True when the output came from the built-in synthetic model, not from the recording. */
export function isSynthetic(modelId: string | null | undefined): boolean {
  return !!modelId && modelId.startsWith("stub-");
}

export function clock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  return `${String(Math.floor(total / 60)).padStart(2, "0")}:${String(total % 60).padStart(2, "0")}`;
}
