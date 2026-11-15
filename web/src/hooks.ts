import { useCallback, useState } from "react";
import { ApiError } from "./api";
import { useI18n } from "./i18n";

/** Runs one action at a time and turns an API refusal into a message in the reader's language. */
export function useAction() {
  const { error } = useI18n();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const run = useCallback(async (action: () => Promise<void>, onFail?: (code: string) => void) => {
    setBusy(true);
    setMessage(null);
    try {
      await action();
    } catch (e) {
      const code = e instanceof ApiError ? e.code : "generic";
      setMessage(error(code));
      onFail?.(code);
    } finally {
      setBusy(false);
    }
  }, [error]);
  return { busy, message, run, setMessage };
}
