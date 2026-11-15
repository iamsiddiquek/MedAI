import { NavLink, Route, Routes } from "react-router-dom";
import { useAuth } from "./auth";
import { useI18n } from "./i18n";
import { AuditPage } from "./pages/Audit";
import { EncounterPage } from "./pages/Encounter";
import { WorklistPage } from "./pages/Worklist";

const AUDIT_ROLES = ["compliance-officer", "security-auditor", "tenant-admin"];

export function App() {
  const { me, signOut } = useAuth();
  const { t, toggle } = useI18n();
  const canAudit = me.roles.some((r) => AUDIT_ROLES.includes(r));
  const canSeeEncounters = me.roles.some((r) => ["clinician", "nurse", "tenant-admin", "compliance-officer"].includes(r));
  return (
    <>
      <header className="topbar">
        <span className="brand">{t("appName")}</span>
        <nav aria-label="Main">
          {canSeeEncounters && <NavLink to="/" end>{t("worklist")}</NavLink>}
          {canAudit && <NavLink to="/audit">{t("audit")}</NavLink>}
        </nav>
        <span className="who">{me.name}</span>
        <button className="btn quiet" onClick={toggle}>{t("language")}</button>
        <button className="btn quiet" onClick={signOut}>{t("signOut")}</button>
      </header>
      <Routes>
        <Route path="/" element={canSeeEncounters ? <WorklistPage /> : <AuditPage />} />
        <Route path="/encounters/:id" element={<EncounterPage />} />
        <Route path="/audit" element={<AuditPage />} />
      </Routes>
    </>
  );
}
