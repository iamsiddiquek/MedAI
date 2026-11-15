/** Deployment settings. Values are public addresses and ids, never secrets. */
export const config = {
  apiBase: import.meta.env.VITE_API_BASE ?? "http://localhost:8080",
  oidcAuthority: import.meta.env.VITE_OIDC_AUTHORITY ?? "http://localhost:8180/realms/afia",
  oidcClientId: import.meta.env.VITE_OIDC_CLIENT_ID ?? "afia-web",
  consentTextVersion: "ambient-consent-v1",
};
