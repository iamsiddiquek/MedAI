import { useCallback, useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { api } from "../api";
import { useAuth } from "../auth";
import { ConsentGate } from "../components/ConsentGate";
import { PatientBanner } from "../components/PatientBanner";
import { Recorder } from "../components/Recorder";
import { Review } from "../components/Review";
import { useAction } from "../hooks";
import { useI18n } from "../i18n";
import type { EncounterView } from "../types";

const POLL_MS = 3000;

export function EncounterPage() {
  const { id = "" } = useParams();
  const { t } = useI18n();
  const { me } = useAuth();
  const [view, setView] = useState<EncounterView | null>(null);
  const { busy, message, run } = useAction();

  const refresh = useCallback(() => api.encounter(id).then(setView), [id]);
  useEffect(() => { void run(refresh); }, [refresh, run]);

  const state = view?.encounter.state;
  const waiting = state === "PROCESSING" && view?.encounter.aiStatus === "OK";
  useEffect(() => {
    if (!waiting) return;
    const timer = window.setInterval(() => void api.encounter(id).then(setView).catch(() => undefined), POLL_MS);
    return () => window.clearInterval(timer);
  }, [id, waiting]);

  if (!view) return <main className="page">{message ? <p role="alert" className="error">{message}</p> : <p aria-busy="true">{t("loading")}…</p>}</main>;

  const owner = me.roles.includes("clinician") && view.encounter.clinicianId === me.subject;
  // Any refusal may mean the encounter moved on (for example consent was withdrawn): show its real state.
  const act = (action: () => Promise<unknown>) =>
    run(async () => { await action(); await refresh(); }, () => void refresh().catch(() => undefined));
  const manual = () => act(() => api.startManualNote(id));
  const revoke = () => { if (window.confirm(t("revokeConfirm"))) void act(() => api.revokeConsent(id)); };
  const canRevoke = owner && (state === "READY" || state === "RECORDING" || state === "PROCESSING");

  return (
    <main className="page">
      <PatientBanner view={view} />
      {message && <p role="alert" className="error">{message}</p>}

      {state === "CONSENT_PENDING" && owner && <ConsentGate view={view} onChange={setView} onManual={() => void manual()} />}
      {(state === "READY" || state === "RECORDING") && owner && <Recorder view={view} onDone={() => void refresh().catch(() => undefined)} />}
      {state === "PROCESSING" && (
        view.encounter.aiStatus === "UNAVAILABLE" ? (
          <section className="panel">
            <h2>{t("aiUnavailable")}</h2>
            <p>{t("aiUnavailableHelp")}</p>
            {owner && <button className="btn primary" disabled={busy} onClick={() => void manual()}>{t("writeByHand")}</button>}
          </section>
        ) : <section className="panel" role="status"><p className="working">{t("processing")}</p></section>
      )}
      {(state === "REVIEW" || state === "SIGNED" || state === "EXPORTED" || state === "CLOSED") && (
        <Review encounterId={id} canEdit={owner} onSigned={() => void refresh().catch(() => undefined)} />
      )}

      {canRevoke && (
        <p className="consent-foot">
          {t("consentOn")}. <button className="link" disabled={busy} onClick={revoke}>{t("revokeConsent")}</button>
        </p>
      )}
    </main>
  );
}
