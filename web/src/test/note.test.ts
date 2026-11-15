import { describe, expect, it } from "vitest";
import { dictionaries } from "../i18n";
import { clock, isSynthetic, signBlocker, unresolved } from "../note";
import type { Note, Statement } from "../types";

const statement = (over: Partial<Statement>): Statement => ({
  ord: 1, text: "Headache for three days.", origin: "AI", supported: true, confidence: 0.9, status: "PENDING",
  evidenceSeqs: [1], ...over,
});

const note = (statements: Statement[], state: Note["state"] = "AI_GENERATED"): Note => ({
  id: "n1", state, origin: "AI", template: "soap", version: 1,
  provenance: { executionId: "e", modelId: "llm-local", modelVersion: "1", promptId: "p", promptVersion: 2, policyVersion: "v", route: "primary" },
  signedBy: null, signedByName: null, signedAt: null, signedVersion: null,
  sections: [{ key: "subjective", title: "Subjective", statements }],
});

describe("sign-off rules shown to the clinician", () => {
  it("blocks signing while a statement that is not in the transcript is unresolved", () => {
    const draft = note([statement({}), statement({ ord: 2, supported: false, evidenceSeqs: [] })]);
    expect(unresolved(draft)).toHaveLength(1);
    expect(signBlocker(draft, true)).toBe("unresolved");
  });

  it("treats an edited or removed statement as resolved", () => {
    const edited = note([statement({ supported: false, status: "REJECTED" }), statement({ ord: 2, status: "EDITED" })]);
    expect(signBlocker(edited, true)).toBeNull();
  });

  it("needs the attestation and some content", () => {
    expect(signBlocker(note([statement({})]), false)).toBe("attest");
    expect(signBlocker(note([statement({ status: "REJECTED" })]), true)).toBe("empty");
    expect(signBlocker(note([]), true)).toBe("empty");
  });

  it("never offers signing for a signed note", () => {
    expect(signBlocker(note([statement({})], "SIGNED"), true)).toBe("signed");
  });
});

describe("display helpers", () => {
  it("marks output of the built-in synthetic model", () => {
    expect(isSynthetic("stub-llm-v1")).toBe(true);
    expect(isSynthetic("llm-local")).toBe(false);
    expect(isSynthetic(null)).toBe(false);
  });

  it("formats transcript times", () => {
    expect(clock(0)).toBe("00:00");
    expect(clock(125_400)).toBe("02:05");
  });

  it("has an Arabic text for every English text", () => {
    expect(Object.keys(dictionaries.ar).sort()).toEqual(Object.keys(dictionaries.en).sort());
    expect(Object.values(dictionaries.ar).every((text) => text.trim().length > 0)).toBe(true);
  });
});
