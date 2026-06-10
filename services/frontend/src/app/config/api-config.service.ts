import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { catchError, firstValueFrom, of } from 'rxjs';

interface RuntimeConfig {
  apiBaseUrl?: string;
}

@Injectable({ providedIn: 'root' })
export class ApiConfigService {
  private readonly http = inject(HttpClient);
  private apiBaseUrl = '';

  load(): Promise<void> {
    return firstValueFrom(
      this.http.get<RuntimeConfig>('/assets/legalgate-config.json').pipe(catchError(() => of({ apiBaseUrl: '' }))),
    ).then((config) => {
      this.setApiBaseUrl(config.apiBaseUrl ?? '');
    });
  }

  setApiBaseUrl(value: string): void {
    this.apiBaseUrl = value.trim().replace(/\/+$/, '');
  }

  url(path: string): string {
    const normalizedPath = path.startsWith('/') ? path : `/${path}`;
    return `${this.apiBaseUrl}${normalizedPath}`;
  }
}
