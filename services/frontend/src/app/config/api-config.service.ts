import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { catchError, firstValueFrom, of } from 'rxjs';

interface RuntimeConfig {
  apiBaseUrl?: string;
  workosClientId?: string;
}

@Injectable({ providedIn: 'root' })
export class ApiConfigService {
  private readonly http = inject(HttpClient);
  private apiBaseUrl = '';
  private workosClientId = '';

  load(): Promise<void> {
    return firstValueFrom(
      this.http.get<RuntimeConfig>('/assets/legalgate-config.json').pipe(
        catchError(() => of<RuntimeConfig>({ apiBaseUrl: '', workosClientId: '' })),
      ),
    ).then((config) => {
      this.setApiBaseUrl(config.apiBaseUrl ?? '');
      this.workosClientId = (config.workosClientId ?? '').trim();
      if (!this.workosClientId) {
        throw new Error('LEGALGATE_WORKOS_CLIENT_ID is missing from runtime configuration.');
      }
    });
  }

  setApiBaseUrl(value: string): void {
    this.apiBaseUrl = value.trim().replace(/\/+$/, '');
  }

  url(path: string): string {
    const normalizedPath = path.startsWith('/') ? path : `/${path}`;
    return `${this.apiBaseUrl}${normalizedPath}`;
  }

  getWorkosClientId(): string {
    return this.workosClientId;
  }

  isGatewayUrl(url: string): boolean {
    const base = this.apiBaseUrl || window.location.origin;
    if (!url.startsWith(base) && /^https?:\/\//.test(url)) {
      return false;
    }
    const path = url.startsWith(base) ? url.slice(base.length) : url;
    return path.startsWith('/api/') && !path.startsWith('/api/status');
  }
}
