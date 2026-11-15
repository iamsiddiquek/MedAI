import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import { useAction } from "../hooks";
import { useI18n, type Key } from "../i18n";
import { clock, isSynthetic, signBlocker, unresolved } from "../note";
import type { Note, NoteVersion, Section, Statement, Transcript } from "../types";

interface Props { encounterId: string; canEdit: boolean; onSigned: () => void }

export function Review({ encounterId, canEdit, onSigned }: Props) {
  const { t } = useI18n();
  const [note, setNote] = useState<Note | null>(null);
  const [transcript, setTranscript] = useState<Transcript | null>(null);
  const [versions, setVersions] = useState<NoteVersion[]>([]);
  const [focused, setFocused] = useState<number[]>([]);
  const [attested, setAttested] = useState(false);
  const { busy, message, run } = useAction();
  const segmentRefs = useRef(new Map<number, HTMLLIElement>());

  useEffect(() => {
    let live = true;
    api.note(encounterId).then((n) => { if (live) setNote(n); }).catch(() => undefined);
    // A handwritten note has no transcript; that is not an error.
    api.transcript(encounterId).then((tr) => { if (live) setTranscript(tr); }).catch(() => undefined);
    return () => { live = false; };
  }, [encounterId]);

  useEffect(() => {
    if (!note) return;
    api.noteVersions(encounterId).then(setVersions).catch(() => undefined);
  }, [encounterId, note]);

  if (!note) return <p aria-busy="true">{t("loading")}…</p>;

  const signed = note.state === "SIGNED";
  const editable = canEdit && !signed;
  const blocker = signBlocker(note, attested);
  const flagged = unresolved(note).length;

  // On a version conflict the server's copy wins: reload it and let the clinician repeat the change.
  const change = (action: () => Promise<Note>) =>
    run(async () => setNote(await action()), (code) => {
      if (code === "stale_version") void api.note(encounterId).then(setNote);
    });

  const showEvidence = (seqs: number[]) => {
    setFocused(seqs);
    const first = seqs[0];
    if (first !== undefined) segmentRefs.current.get(first)?.scrollIntoView({ block: "center", behavior: "smooth" });
  };

  return (
    <div className="review">
      {isSynthetic(note.provenance.modelId) && <p className="banner synthetic" role="note">{t("syntheticBanner")}</p>}
      {!signed && <p className="banner draft" role="note">{t(note.origin === "AI" ? "draftBanner" : "manualBanner")}</p>}
      {signed && (
        <p className="banner signed" role="note">
          {t("signedBy")} <strong>{note.signedByName}</strong>, <bdi>{note.signedAt && new Date(note.signedAt).toLocaleString()}</bdi>. {t("signedNote")}
        </p>
      )}
      {message && <p role="alert" className="error">{message}</p>}

      <div className="panes">
        {transcript && (
          <section className="pane" aria-label={t("transcript")}>
            <h2>{t("transcript")}</h2>
            <ol className="segments">
              {transcript.segments.map((s) => (
                <li key={s.seq} ref={(el) => { if (el) segmentRefs.current.set(s.seq, el); }}
                    className={focused.includes(s.seq) ? "segment cited" : "segment"}>
                  <span className="seq">{s.seq}</span>
                  <div>
                    <span className="meta">{t(speakerKey(s.speaker))}, <bdi>{clock(s.startMs)}</bdi></span>
                    <p lang={s.language} dir="auto">{s.text}</p>
                  </div>
                </li>
              ))}
            </ol>
          </section>
        )}

        <section className="pane" aria-label={t("note")}>
          <h2>{t("note")}</h2>
          {note.sections.map((section) => (
            <SectionView key={section.key} section={section} editable={editable} busy={busy}
                         onEvidence={showEvidence}
                         onReview={(ord, action, text) => void change(() =>
                           api.reviewStatement(encounterId, section.key, ord, action, note.version, text))}
                         onAdd={(text) => void change(() => api.addStatement(encounterId, section.key, text, note.version))} />
          ))}

          {editable && (
            <div className="sign">
              <h3>{t("signTitle")}</h3>
              {flagged > 0 && <p className="warn">{t("blockerUnresolved")} ({flagged})</p>}
              <label className="confirm">
                <input type="checkbox" checked={attested} onChange={(e) => setAttested(e.target.checked)} />
                <span>{t("attest")}</span>
              </label>
              {/* Deliberately never focused by default: signing takes a separate, intended action. */}
              <button className="btn primary" disabled={blocker !== null || busy}
                      onClick={() => void run(async () => { setNote(await api.sign(encounterId, note.version)); onSigned(); })}>
                {t("sign")}
              </button>
              {blocker && blocker !== "signed" && blocker !== "unresolved" && (
                <p className="hint">{t(blocker === "empty" ? "blockerEmpty" : "blockerAttest")}</p>
              )}
            </div>
          )}

          {note.origin === "AI" && (
            <details className="provenance">
              <summary>{t("madeBy")}</summary>
              <dl>
                <dt>{t("model")}</dt><dd><bdi>{note.provenance.modelId} {note.provenance.modelVersion}</bdi></dd>
                <dt>{t("prompt")}</dt><dd><bdi>{note.provenance.promptId} v{note.provenance.promptVersion}</bdi></dd>
                <dt>{t("policy")}</dt><dd><bdi>{note.provenance.policyVersion}</bdi></dd>
                <dt>{t("route")}</dt><dd><bdi>{note.provenance.route}</bdi></dd>
              </dl>
            </details>
          )}
          <details className="provenance">
            <summary>{t("history")} ({versions.length})</summary>
            <ol>
              {versions.map((v) => (
                <li key={v.version}><bdi>v{v.version}</bdi> <bdi>{new Date(v.createdAt).toLocaleString()}</bdi> <bdi>{v.summary}</bdi></li>
              ))}
            </ol>
          </details>
        </section>
      </div>
    </div>
  );
}

function speakerKey(speaker: string): Key {
  return speaker === "clinician" ? "speaker_clinician" : speaker === "patient" ? "speaker_patient" : "speaker_unknown";
}

interface SectionProps {
  section: Section; editable: boolean; busy: boolean;
  onEvidence: (seqs: number[]) => void;
  onReview: (ord: number, action: "ACCEPT" | "EDIT" | "REJECT", text?: string) => void;
  onAdd: (text: string) => void;
}

export function SectionView({ section, editable, busy, onEvidence, onReview, onAdd }: SectionProps) {
  const { t } = useI18n();
  const [draft, setDraft] = useState("");
  return (
    <div className="section">
      <h3>{t(`section_${section.key}` as Key)}</h3>
      <ul className="statements">
        {section.statements.map((st) => (
          <StatementView key={st.ord} statement={st} editable={editable} busy={busy} onEvidence={onEvidence}
                         onReview={(action, text) => onReview(st.ord, action, text)} />
        ))}
      </ul>
      {editable && (
        <form className="add" onSubmit={(e) => { e.preventDefault(); if (draft.trim()) { onAdd(draft.trim()); setDraft(""); } }}>
          <input aria-label={`${t("addStatement")}: ${t(`section_${section.key}` as Key)}`} dir="auto" value={draft}
                 placeholder={t("addStatement")} onChange={(e) => setDraft(e.target.value)} />
          <button className="btn" disabled={busy || !draft.trim()}>{t("add")}</button>
        </form>
      )}
    </div>
  );
}

interface StatementProps {
  statement: Statement; editable: boolean; busy: boolean;
  onEvidence: (seqs: number[]) => void;
  onReview: (action: "ACCEPT" | "EDIT" | "REJECT", text?: string) => void;
}

export function StatementView({ statement: st, editable, busy, onEvidence, onReview }: StatementProps) {
  const { t } = useI18n();
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(st.text);
  const needsAction = !st.supported && st.status === "PENDING";
  const rejected = st.status === "REJECTED";
  return (
    <li className={`statement${needsAction ? " flagged" : ""}${rejected ? " rejected" : ""}`}>
      {editing ? (
        <form onSubmit={(e) => { e.preventDefault(); if (text.trim()) { onReview("EDIT", text.trim()); setEditing(false); } }}>
          <textarea dir="auto" value={text} rows={3} onChange={(e) => setText(e.target.value)} />
          <div className="actions">
            <button className="btn primary" disabled={busy || !text.trim()}>{t("save")}</button>
            <button type="button" className="btn" onClick={() => { setEditing(false); setText(st.text); }}>{t("cancel")}</button>
          </div>
        </form>
      ) : (
        <>
          <p dir="auto">
            {st.text}{" "}
            {st.evidenceSeqs.map((seq) => (
              <button key={seq} className="cite" title={`${t("evidence")} ${seq}`} aria-label={`${t("evidence")} ${seq}`}
                      onClick={() => onEvidence([seq])}>{seq}</button>
            ))}
          </p>
          {needsAction && <p className="flag">{t("unsupported")}</p>}
          <div className="statement-foot">
            {st.status === "ACCEPTED" && <span className="mark">{t("kept")}</span>}
            {st.status === "EDITED" && <span className="mark">{t("editedByYou")}</span>}
            {rejected && <span className="mark">{t("removed")}</span>}
            {editable && !rejected && (
              <span className="actions">
                {st.status === "PENDING" && st.supported && (
                  <button className="btn small" disabled={busy} onClick={() => onReview("ACCEPT")}>{t("accept")}</button>
                )}
                <button className="btn small" disabled={busy} onClick={() => setEditing(true)}>{t("edit")}</button>
                <button className="btn small" disabled={busy} onClick={() => onReview("REJECT")}>{t("reject")}</button>
              </span>
            )}
          </div>
        </>
      )}
    </li>
  );
}
