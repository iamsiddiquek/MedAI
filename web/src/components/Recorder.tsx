import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "../api";
import { useAction } from "../hooks";
import { useI18n } from "../i18n";
import { clock } from "../note";
import type { EncounterView } from "../types";

interface Props { view: EncounterView; onDone: () => void }

/**
 * Capture happens only after consent and after the clinician confirms the patient.
 * The whole recording is sent when it stops; resumable chunked upload is a later slice.
 */
export function Recorder({ view, onDone }: Props) {
  const { t } = useI18n();
  const { busy, message, run, setMessage } = useAction();
  const [confirmed, setConfirmed] = useState(false);
  const [recordingId, setRecordingId] = useState<string | null>(null);
  const [live, setLive] = useState(false);
  const [sending, setSending] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [file, setFile] = useState<File | null>(null);
  const recorder = useRef<MediaRecorder | null>(null);
  const chunks = useRef<Blob[]>([]);
  const id = view.encounter.id;
  const interrupted = view.encounter.state === "RECORDING" && !live && !sending;

  // After a reload the server still has an active recording: pick it up instead of starting another.
  useEffect(() => {
    if (view.encounter.state !== "RECORDING" || recordingId) return;
    api.activeRecording(id).then((r) => setRecordingId(r.id)).catch(() => undefined);
  }, [id, recordingId, view.encounter.state]);

  useEffect(() => {
    if (!live) return;
    const started = Date.now();
    const timer = window.setInterval(() => setElapsed(Date.now() - started), 500);
    return () => window.clearInterval(timer);
  }, [live]);

  useEffect(() => () => recorder.current?.stream.getTracks().forEach((track) => track.stop()), []);

  // If sending fails the recording stays active on the server, and this screen offers the file route.
  const send = (activeId: string, audio: Blob) =>
    run(async () => {
      await api.uploadAudio(id, activeId, audio);
      onDone();
    }, () => setSending(false));

  const start = () =>
    run(async () => {
      let stream: MediaStream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      } catch {
        setMessage(t("micDenied"));
        return;
      }
      let started;
      try {
        started = await api.startRecording(id);
      } catch (e) {
        stream.getTracks().forEach((track) => track.stop());
        throw e instanceof ApiError ? e : new ApiError(0, "generic");
      }
      const media = new MediaRecorder(stream);
      chunks.current = [];
      media.ondataavailable = (event) => { if (event.data.size > 0) chunks.current.push(event.data); };
      media.onstop = () => {
        stream.getTracks().forEach((track) => track.stop());
        setLive(false);
        setSending(true);
        void send(started.id, new Blob(chunks.current, { type: media.mimeType || "audio/webm" }));
      };
      media.start(1000);
      recorder.current = media;
      setRecordingId(started.id);
      setLive(true);
    });

  const sendFile = () =>
    run(async () => {
      if (!file) return;
      const activeId = recordingId ?? (await api.startRecording(id)).id;
      setRecordingId(activeId);
      await api.uploadAudio(id, activeId, file);
      onDone();
    });

  return (
    <section className="panel">
      {live ? (
        <div className="recording" role="status">
          <span className="rec-light" aria-hidden="true" />
          <strong>{t("recordingOn")}</strong>
          <span className="timer">{clock(elapsed)}</span>
          <button className="btn primary" disabled={busy} onClick={() => recorder.current?.stop()}>{t("stopRecording")}</button>
        </div>
      ) : sending ? <p className="working" role="status">{t("sendAudio")}…</p> : (
        <>
          {interrupted ? <p role="alert" className="warn">{t("interrupted")}</p> : (
            <>
              <label className="confirm">
                <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
                <span>{t("confirmPatient")} <strong dir="auto">{view.patient?.fullName}</strong> (<bdi>{view.patient?.mrn}</bdi>)</span>
              </label>
              <div className="actions">
                <button className="btn primary" disabled={!confirmed || busy} onClick={() => void start()}>{t("startRecording")}</button>
              </div>
            </>
          )}
          <div className="file-row">
            <label htmlFor="audio-file">{t("uploadInstead")}</label>
            <input id="audio-file" type="file" accept="audio/*" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            <button className="btn" disabled={!file || busy || (!confirmed && !interrupted)} onClick={() => void sendFile()}>{t("sendAudio")}</button>
          </div>
        </>
      )}
      {message && <p role="alert" className="error">{message}</p>}
    </section>
  );
}
