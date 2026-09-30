import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { catchError, firstValueFrom, of } from 'rxjs';

interface RuntimeConfig {
  apiBaseUrl?: string;
  clerkPublishableKey?: string;
}

@Injectable({ providedIn: 'root' })
export class ApiConfigService {
  private readonly http = inject(HttpClient);
  private apiBaseUrl = '';
  private clerkPublishableKey = '';

  load(): Promise<void> {
    return firstValueFrom(
      this.http.get<RuntimeConfig>('/assets/legalgate-config.json').pipe(
        catchError(() => of<RuntimeConfig>({ apiBaseUrl: '', clerkPublishableKey: '' })),
      ),
    ).then((config) => {
      this.setApiBaseUrl(config.apiBaseUrl ?? '');
      this.clerkPublishableKey = (config.clerkPublishableKey ?? '').trim();
      if (!this.clerkPublishableKey) {
        throw new Error('LEGALGATE_CLERK_PUBLISHABLE_KEY is missing from runtime configuration.');
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

  getClerkPublishableKey(): string {
    return this.clerkPublishableKey;
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
