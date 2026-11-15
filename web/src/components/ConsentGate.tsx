import { useState } from "react";
import { api } from "../api";
import { useAction } from "../hooks";
import { dictionaries, useI18n, type Lang } from "../i18n";
import type { EncounterView } from "../types";

interface Props { view: EncounterView; onChange: (view: EncounterView) => void; onManual: () => void }

export function ConsentGate({ view, onChange, onManual }: Props) {
  const { t, lang } = useI18n();
  const [readIn, setReadIn] = useState<Lang>(lang);
  const { busy, message, run } = useAction();
  const id = view.encounter.id;
  const [asking, setAsking] = useState(false);
  const refused = view.consent?.decision === "REFUSED" && !asking;
  const decide = (decision: "GRANTED" | "REFUSED") =>
    run(async () => onChange(await api.recordConsent(id, decision, readIn)));

  if (refused) {
    return (
      <section className="panel">
        <p>{t("consentRefusedInfo")}</p>
        <div className="actions">
          <button className="btn primary" onClick={onManual}>{t("writeByHand")}</button>
          <button className="btn" onClick={() => setAsking(true)}>{t("askAgain")}</button>
        </div>
      </section>
    );
  }
  return (
    <section className="panel">
      <h2>{t("consentTitle")}</h2>
      {/* The text is shown in the language it is read to the patient, whatever the screen language. */}
      <blockquote className="consent-text" lang={readIn} dir={readIn === "ar" ? "rtl" : "ltr"}>
        {dictionaries[readIn].consentText}
      </blockquote>
      <fieldset className="inline">
        <legend>{t("consentReadIn")}</legend>
        <label><input type="radio" name="readIn" checked={readIn === "en"} onChange={() => setReadIn("en")} /> {t("english")}</label>
        <label><input type="radio" name="readIn" checked={readIn === "ar"} onChange={() => setReadIn("ar")} /> {t("arabic")}</label>
      </fieldset>
      <div className="actions">
        <button className="btn primary" disabled={busy} onClick={() => void decide("GRANTED")}>{t("consentGranted")}</button>
        <button className="btn" disabled={busy} onClick={() => void decide("REFUSED")}>{t("consentRefused")}</button>
      </div>
      {message && <p role="alert" className="error">{message}</p>}
      <p className="consent-foot"><button className="link" onClick={onManual}>{t("writeByHand")}</button></p>
    </section>
  );
}
