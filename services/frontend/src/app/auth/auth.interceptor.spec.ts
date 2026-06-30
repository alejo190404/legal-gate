import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { ApiConfigService } from '../config/api-config.service';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  const auth = { getAccessToken: vi.fn() };

  beforeEach(() => {
    auth.getAccessToken.mockReset().mockResolvedValue('token-1');
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        ApiConfigService,
        { provide: AuthService, useValue: auth },
      ],
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('adds a bearer token only to Gateway API calls', async () => {
    const http = TestBed.inject(HttpClient);
    const controller = TestBed.inject(HttpTestingController);

    http.get('/assets/legalgate-config.json').subscribe();
    const asset = controller.expectOne('/assets/legalgate-config.json');
    expect(asset.request.headers.has('Authorization')).toBe(false);
    asset.flush({});

    http.get('/api/session').subscribe();
    await Promise.resolve();
    const api = controller.expectOne('/api/session');
    expect(api.request.headers.get('Authorization')).toBe('Bearer token-1');
    api.flush({});

    const absoluteUrl = `${window.location.origin}/api/consultations`;
    http.get(absoluteUrl).subscribe();
    await Promise.resolve();
    const absoluteApi = controller.expectOne(absoluteUrl);
    expect(absoluteApi.request.headers.get('Authorization')).toBe('Bearer token-1');
    absoluteApi.flush({});
  });

  it('force-refreshes once after a 401', async () => {
    auth.getAccessToken.mockResolvedValueOnce('stale').mockResolvedValueOnce('fresh');
    const http = TestBed.inject(HttpClient);
    const controller = TestBed.inject(HttpTestingController);

    http.get('/api/session').subscribe();
    await Promise.resolve();
    controller.expectOne('/api/session').flush({}, { status: 401, statusText: 'Unauthorized' });
    await Promise.resolve();
    const retry = controller.expectOne('/api/session');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer fresh');
    retry.flush({});
    expect(auth.getAccessToken).toHaveBeenNthCalledWith(1, false);
    expect(auth.getAccessToken).toHaveBeenNthCalledWith(2, true);
  });

  it('does not retry a forbidden response', async () => {
    const http = TestBed.inject(HttpClient);
    const controller = TestBed.inject(HttpTestingController);

    http.get('/api/session').subscribe({ error: () => undefined });
    await Promise.resolve();
    controller.expectOne('/api/session').flush({}, { status: 403, statusText: 'Forbidden' });
    expect(auth.getAccessToken).toHaveBeenCalledOnce();
  });
});
