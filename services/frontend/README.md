# LegalGate frontend

Angular SPA containing the public landing page and authenticated firm console. Authentication is
provided by WorkOS AuthKit Hosted UI; the browser never stores access or refresh tokens manually.

Required build variables:

- `LEGALGATE_WORKOS_CLIENT_ID`
- `LEGALGATE_API_BASE_URL` (empty only when the host reverse-proxies `/api` to Gateway)

Local development:

```bash
export LEGALGATE_WORKOS_CLIENT_ID=client_test_...
npm install
npm start
```

Verification:

```bash
npm test
npm run build
npm audit --omit=dev
```

See [WorkOS production setup](../../docs/deployment/workos-authkit.md).
