# Nexus PIM frontend

React + TypeScript + Vite, React Router, TanStack Query, Vitest and React Testing Library.
Requires Node >=22.12. Run `npm ci`, then `npm run dev`; open http://127.0.0.1:5173/categories.
Taxonomy is proxied to http://localhost:8083. Set TAXONOMY_API_URL to change that development target.
Production hosting must proxy `/api/taxonomy` to the same backend; Vite's proxy applies to development only.

The first slice supports category lookup by code, creation and name/parent updates. The current backend
controller has no category list or delete endpoint. Other services are visibly marked Not connected.
No login/auth integration has been verified. Backend availability is required for actual saves.
Tests mock the typed API client; passing them does not establish database or network integration.
Run `npm run verify` for type-checking, production build and fresh test evidence.
