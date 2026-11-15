import { useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api } from "../api";
import { useAuth } from "../auth";
import { useAction } from "../hooks";
import { useI18n, type Key } from "../i18n";
import type { EncounterView, Patient } from "../types";

export function WorklistPage() {
  const { t } = useI18n();
  const { me } = useAuth();
  const navigate = useNavigate();
  const [encounters, setEncounters] = useState<EncounterView[] | null>(null);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<Patient[] | null>(null);
  const [form, setForm] = useState({ fullName: "", mrn: "", mrnIssuer: "", dateOfBirth: "", sex: "" });
  const { busy, message, run } = useAction();
  const isClinician = me.roles.includes("clinician");

  useEffect(() => { void run(async () => setEncounters(await api.encounters())); }, [run]);

  const search = (e: FormEvent) => { e.preventDefault(); void run(async () => setResults(await api.searchPatients(query))); };
  const begin = (patientId: string) =>
    run(async () => navigate(`/encounters/${(await api.createEncounter(patientId)).encounter.id}`));
  const register = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => {
      const patient = await api.createPatient({ ...form, sex: form.sex || null });
      setResults([patient]);
    });
  };
  const field = (name: keyof typeof form, label: Key, type = "text", required = true) => (
    <label>{t(label)}
      <input type={type} dir="auto" required={required} value={form[name]}
             onChange={(e) => setForm({ ...form, [name]: e.target.value })} />
    </label>
  );

  return (
    <main className="page">
      {message && <p role="alert" className="error">{message}</p>}
      {isClinician && (
        <section className="panel">
          <h2>{t("newEncounter")}</h2>
          <form className="search" onSubmit={search}>
            <input aria-label={t("findPatient")} placeholder={t("findPatient")} dir="auto" value={query}
                   onChange={(e) => setQuery(e.target.value)} />
            <button className="btn" disabled={busy}>{t("search")}</button>
          </form>
          {results && results.length === 0 && <p className="hint">{t("noPatients")}</p>}
          {results && results.length > 0 && (
            <ul className="rows">
              {results.map((p) => (
                <li key={p.id}>
                  <span><strong dir="auto">{p.fullName}</strong> <bdi>{p.mrn}</bdi> <bdi>{p.dateOfBirth}</bdi></span>
                  <button className="btn primary" disabled={busy} onClick={() => void begin(p.id)}>{t("startEncounter")}</button>
                </li>
              ))}
            </ul>
          )}
          <details>
            <summary>{t("registerPatient")}</summary>
            <form className="grid" onSubmit={register}>
              {field("fullName", "fullName")}
              {field("mrn", "mrn")}
              {field("mrnIssuer", "mrnIssuer")}
              {field("dateOfBirth", "dateOfBirth", "date")}
              {field("sex", "sex", "text", false)}
              <button className="btn" disabled={busy}>{t("register")}</button>
            </form>
          </details>
        </section>
      )}

      <section>
        <h2>{t("worklist")}</h2>
        {encounters === null ? <p aria-busy="true">{t("loading")}…</p>
          : encounters.length === 0 ? <p className="hint">{t("noEncounters")}</p> : (
            <ul className="rows">
              {encounters.map(({ encounter, patient }) => (
                <li key={encounter.id}>
                  <span>
                    <strong dir="auto">{patient?.fullName}</strong> <bdi>{patient?.mrn}</bdi>{" "}
                    <bdi>{new Date(encounter.createdAt).toLocaleString()}</bdi>
                  </span>
                  <span className={`state state-${encounter.state}`}>{t(`state${encounter.state}` as Key)}</span>
                  <Link className="btn" to={`/encounters/${encounter.id}`}>{t("open")}</Link>
                </li>
              ))}
            </ul>
          )}
      </section>
    </main>
  );
}
