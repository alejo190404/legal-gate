import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const configPath = resolve('src/assets/legalgate-config.json');
const apiBaseUrl = (process.env.LEGALGATE_API_BASE_URL ?? '').trim().replace(/\/+$/, '');
const clerkPublishableKey = (process.env.LEGALGATE_CLERK_PUBLISHABLE_KEY ?? '').trim();
if (!clerkPublishableKey) {
  throw new Error('LEGALGATE_CLERK_PUBLISHABLE_KEY must be configured.');
}
const config = `${JSON.stringify({ apiBaseUrl, clerkPublishableKey }, null, 2)}
`;
mkdirSync(dirname(configPath), { recursive: true });
writeFileSync(configPath, config);
console.log(`Wrote LegalGate runtime config to ${configPath}`);
