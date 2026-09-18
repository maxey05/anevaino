import { defineConfig, configDefaults } from "vitest/config";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: { manifest: true },
  server: { proxy: { "/api": "http://localhost:8080" } },
  test: { environment: "jsdom", exclude: [...configDefaults.exclude, "e2e/**"] },
});
