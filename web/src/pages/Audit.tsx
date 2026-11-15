import { useState, type FormEvent } from "react";
import { api } from "../api";
import { useAction } from "../hooks";
import { useI18n } from "../i18n";
import type { AuditEvent, AuditVerification } from "../types";

export function AuditPage() {
  const { t } = useI18n();
  const [resourceId, setResourceId] = useState("");
  const [events, setEvents] = useState<AuditEvent[] | null>(null);
  const [check, setCheck] = useState<AuditVerification | null>(null);
  const { busy, message, run } = useAction();
  const show = (e: FormEvent) => { e.preventDefault(); void run(async () => setEvents(await api.auditEvents(resourceId.trim()))); };

  return (
    <main className="page">
      <h1>{t("auditTitle")}</h1>
      <section className="panel">
        <button className="btn" disabled={busy} onClick={() => void run(async () => setCheck(await api.auditVerify()))}>{t("verifyChain")}</button>
        {check && (
          <p className={check.valid ? "ok" : "error"} role="status">
            {check.valid ? t("chainValid") : `${t("chainBroken")} ${check.firstBrokenSeq}`} (<bdi>{check.events}</bdi> {t("entries")})
          </p>
        )}
      </section>
      <section className="panel">
        <p className="hint">{t("auditHelp")}</p>
        <form className="search" onSubmit={show}>
          <input aria-label={t("resourceId")} placeholder={t("resourceId")} value={resourceId} dir="ltr"
                 onChange={(e) => setResourceId(e.target.value)} />
          <button className="btn" disabled={busy || !resourceId.trim()}>{t("show")}</button>
        </form>
        {message && <p role="alert" className="error">{message}</p>}
        {events && events.length === 0 && <p className="hint">{t("noAudit")}</p>}
        {events && events.length > 0 && (
          <table>
            <thead><tr><th>{t("when")}</th><th>{t("who")}</th><th>{t("action")}</th></tr></thead>
            <tbody>
              {events.map((e) => (
                <tr key={e.seq}>
                  <td><bdi>{new Date(e.occurredAt).toLocaleString()}</bdi></td><td><bdi>{e.actor}</bdi></td><td><bdi>{e.action}</bdi></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}
