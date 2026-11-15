import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from "oidc-client-ts";
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { api, configureApi } from "./api";
import { config } from "./config";
import type { Me } from "./types";

/**
 * Sign-in with the authorization-code flow and PKCE. Tokens are kept in memory only, so
 * nothing sensitive is left in browser storage; a reload signs in again silently through
 * the identity provider's own session.
 */
const manager = new UserManager({
  authority: config.oidcAuthority,
  client_id: config.oidcClientId,
  redirect_uri: `${window.location.origin}/callback`,
  post_logout_redirect_uri: window.location.origin,
  response_type: "code",
  scope: "openid profile",
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  automaticSilentRenew: true, // renews with the refresh token; no hidden frame is used
});

interface Auth { me: Me; signOut: () => void }

const Ctx = createContext<Auth | null>(null);

let user: User | null = null;
let booting: Promise<Me | null> | null = null;

/** Runs once per page load, even when React mounts the provider twice in development. */
function boot(): Promise<Me | null> {
  booting ??= (async () => {
    manager.events.addUserLoaded((renewed) => { user = renewed; });
    const refresh = async () => {
      try {
        user = await manager.signinSilent();
        return user !== null;
      } catch {
        return false;
      }
    };
    const signIn = () => void manager.signinRedirect({ state: window.location.pathname });
    configureApi(() => user?.access_token ?? null, refresh, signIn);
    if (window.location.pathname !== "/callback") {
      signIn();
      return null;
    }
    user = await manager.signinRedirectCallback();
    const wanted = typeof user.state === "string" && /^\/[^/]/.test(user.state) ? user.state : "/";
    window.history.replaceState({}, "", wanted === "/callback" ? "/" : wanted);
    return api.me();
  })();
  return booting;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let live = true;
    boot().then((result) => { if (live && result) setMe(result); }).catch(() => { if (live) setFailed(true); });
    return () => { live = false; };
  }, []);

  if (failed) {
    return (
      <main className="centered">
        <p>Sign-in did not complete.</p>
        <button className="btn" onClick={() => void manager.signinRedirect()}>Sign in again</button>
      </main>
    );
  }
  if (!me) return <main className="centered" aria-busy="true">…</main>;
  return <Ctx.Provider value={{ me, signOut: () => void manager.signoutRedirect() }}>{children}</Ctx.Provider>;
}

export function useAuth(): Auth {
  const value = useContext(Ctx);
  if (!value) throw new Error("AuthProvider is missing");
  return value;
}
