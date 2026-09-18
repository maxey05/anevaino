import { readFileSync } from "node:fs";

const manifest = JSON.parse(readFileSync("dist/.vite/manifest.json", "utf8"));

const entry = Object.values(manifest).find((chunk) => chunk.isEntry);
if (!entry) {
  console.error("No entry chunk found in the Vite manifest.");
  process.exit(1);
}

const visited = new Set();

function collectStaticImports(chunk) {
  if (!chunk || visited.has(chunk.file)) return;
  visited.add(chunk.file);
  for (const importedKey of chunk.imports ?? []) {
    collectStaticImports(manifest[importedKey]);
  }
}

collectStaticImports(entry);

const offender = [...visited].find((file) => file.includes("xyflow"));
if (offender) {
  console.error(
    `Landing bundle pulls in React Flow via a static import: ${offender}`,
  );
  process.exit(1);
}

console.log("Landing bundle is clean of @xyflow/react.");
