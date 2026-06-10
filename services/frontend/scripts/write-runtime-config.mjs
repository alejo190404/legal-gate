import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const configPath = resolve('src/assets/legalgate-config.json');
const apiBaseUrl = (process.env.LEGALGATE_API_BASE_URL ?? '').trim().replace(/\/+$/, '');
const config = `${JSON.stringify({ apiBaseUrl }, null, 2)}
`;
mkdirSync(dirname(configPath), { recursive: true });
writeFileSync(configPath, config);
console.log(`Wrote LegalGate runtime config to ${configPath}`);
