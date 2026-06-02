# Frontend service

Angular 21 landing page for LegalGate clients in Colombia. The implementation intentionally stays scoped to the public marketing landing page: no dashboard, console, authentication screens, or private workflows are implemented here.

## Design system usage

The page uses the exported LegalGate design system primitives:

- Gate Orange `#FA5410`
- warm paper/stone neutral palette
- Space Grotesk + JetBrains Mono
- sharp pixel-native corners
- hard offset pixel shadows
- copied LegalGate SVG logo, pixel pattern, and pixel icon assets in `public/assets/`

## Local development

```bash
npm install
npm start
```

Open `http://localhost:4200`.

## Verification

```bash
npm test -- --watch=false
npm run build
```

Or from the repository root:

```bash
./scripts/test-frontend-local.sh
```

## Docker

```bash
docker compose build frontend
docker compose up frontend
```

Open `http://localhost:4200`.
