#!/bin/bash
# Regenerate bindings from the module and bundle the SpacetimeDB TypeScript SDK client used by the console.
set -e
cd "$(dirname "$0")"
npm install
spacetime generate --lang typescript --out-dir ./bindings --module-path ../server/spacetimedb
npx esbuild entry.ts --bundle --format=esm --platform=browser --target=es2020 --minify --outfile=dist/stdb-client.mjs
