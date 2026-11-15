import js from "@eslint/js";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    rules: {
      // Clinical content must never reach the browser console.
      "no-console": "error",
    },
  },
);
