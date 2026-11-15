import { useI18n, type Key } from "../i18n";
import type { EncounterView } from "../types";

/** Always visible while an encounter is open, so the clinician can see whose record this is. */
export function PatientBanner({ view }: { view: EncounterView }) {
  const { t } = useI18n();
  const { patient, encounter } = view;
  return (
    <section className="patient-banner" aria-label={t("patient")}>
      <div>
        <h1 dir="auto">{patient?.fullName ?? "—"}</h1>
        <p>
          <span>{t("mrn")}: <bdi>{patient?.mrn}</bdi></span>
          <span>{t("born")}: <bdi>{patient?.dateOfBirth}</bdi></span>
          <span>{t("clinician")}: <bdi>{encounter.clinicianName}</bdi></span>
        </p>
      </div>
      <span className={`state state-${encounter.state}`}>{t(`state${encounter.state}` as Key)}</span>
    </section>
  );
}
