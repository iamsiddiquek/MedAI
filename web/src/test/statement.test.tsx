// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { StatementView } from "../components/Review";
import { I18nProvider } from "../i18n";
import type { Statement } from "../types";

const base: Statement = {
  ord: 1, text: "Start amoxicillin.", origin: "AI", supported: false, confidence: 0.8, status: "PENDING", evidenceSeqs: [],
};

function show(statement: Statement, editable = true) {
  const onReview = vi.fn();
  const onEvidence = vi.fn();
  render(
    <I18nProvider>
      <ul><StatementView statement={statement} editable={editable} busy={false} onReview={onReview} onEvidence={onEvidence} /></ul>
    </I18nProvider>,
  );
  return { onReview, onEvidence };
}

afterEach(cleanup);

describe("a statement in the draft", () => {
  it("cannot simply be kept when it is not in the transcript", () => {
    const { onReview } = show(base);
    expect(screen.getByText(/Not found in the transcript/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Keep" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Remove" }));
    expect(onReview).toHaveBeenCalledWith("REJECT");
  });

  it("can be kept when the transcript supports it, and shows its evidence", () => {
    const { onReview, onEvidence } = show({ ...base, supported: true, evidenceSeqs: [4] });
    fireEvent.click(screen.getByRole("button", { name: "Shows transcript line 4" }));
    expect(onEvidence).toHaveBeenCalledWith([4]);
    fireEvent.click(screen.getByRole("button", { name: "Keep" }));
    expect(onReview).toHaveBeenCalledWith("ACCEPT");
  });

  it("sends the clinician's corrected text", () => {
    const { onReview } = show(base);
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "  Patient denies drug allergies.  " } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(onReview).toHaveBeenCalledWith("EDIT", "Patient denies drug allergies.");
  });

  it("offers no actions on a signed or read-only note", () => {
    show({ ...base, supported: true, evidenceSeqs: [1] }, false);
    expect(screen.queryByRole("button", { name: "Edit" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Remove" })).toBeNull();
  });
});
