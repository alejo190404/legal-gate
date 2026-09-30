# LegalGate frontend

Angular SPA containing the public landing page and authenticated firm console. Authentication is
provided by the Clerk Account Portal; the browser never stores access or refresh tokens manually.

Required build variables:

- `LEGALGATE_CLERK_PUBLISHABLE_KEY`
- `LEGALGATE_API_BASE_URL` (empty only when the host reverse-proxies `/api` to Gateway)

Local development:

```bash
export LEGALGATE_CLERK_PUBLISHABLE_KEY=pk_test_...
npm install
npm start
```

Verification:

```bash
npm test
npm run build
npm audit --omit=dev
```

See [Clerk production setup](../../docs/deployment/clerk-auth.md).
