import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const configPath = resolve('src/assets/legalgate-config.json');
const apiBaseUrl = (process.env.LEGALGATE_API_BASE_URL ?? '').trim().replace(/\/+$/, '');
const workosClientId = (process.env.LEGALGATE_WORKOS_CLIENT_ID ?? '').trim();
if (!workosClientId) {
  throw new Error('LEGALGATE_WORKOS_CLIENT_ID must be configured.');
}
const config = `${JSON.stringify({ apiBaseUrl, workosClientId }, null, 2)}
`;
mkdirSync(dirname(configPath), { recursive: true });
writeFileSync(configPath, config);
console.log(`Wrote LegalGate runtime config to ${configPath}`);
