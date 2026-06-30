import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { App } from './app';
import { AuthService } from './auth/auth.service';
import { ApiConfigService } from './config/api-config.service';

describe('App WorkOS authentication flow', () => {
  const user = signal<any>(null);
  const hasOrganization = signal(false);
  const auth = {
    user,
    hasOrganization,
    signIn: vi.fn().mockResolvedValue(undefined),
    signUp: vi.fn().mockResolvedValue(undefined),
    signOut: vi.fn(),
    switchToOrganization: vi.fn().mockResolvedValue(undefined),
    getAccessToken: vi.fn().mockResolvedValue('access-token'),
  };

  beforeEach(async () => {
    user.set(null);
    hasOrganization.set(false);
    vi.clearAllMocks();
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        ApiConfigService,
      ],
    }).compileComponents();
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('keeps the landing public and contains no local password form', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const html = fixture.nativeElement as HTMLElement;

    expect(html.querySelector('.landing-shell')).toBeTruthy();
    expect(html.querySelector('input[type="password"]')).toBeFalsy();
    expect(html.textContent).not.toContain('LegalGateDemo2026!');
  });

  it('uses AuthKit redirects for sign-in and sign-up', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    fixture.componentInstance.showLogin();
    fixture.componentInstance.showRegister();

    expect(auth.signIn).toHaveBeenCalledOnce();
    expect(auth.signUp).toHaveBeenCalledOnce();
  });

  it('restores an organization session and only calls tenant-neutral APIs', () => {
    user.set({ id: 'user_1', email: 'admin@firma.test' });
    hasOrganization.set(true);
    const fixture = TestBed.createComponent(App);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    http.expectOne('/api/session').flush({
      userId: 'user_1',
      sessionId: 'session_1',
      organizationId: 'org_1',
      tenantId: 'firma-1',
      displayName: 'Firma 1',
      role: 'firm_admin',
    });
    http.expectOne('/api/billing/subscription').flush({
      billingEnabled: false,
      enforcementEnabled: false,
      entitled: true,
      status: 'DISABLED',
      subscription: null,
      payments: [],
      accessEndsAt: null,
      message: 'Billing is disabled for this environment.',
    });
    http.expectOne('/api/consultations').flush({ tenantId: 'firma-1', consultations: [] });
    http.expectOne('/api/tenant/settings').flush({
      tenantId: 'firma-1',
      urgentKeywords: [],
      consultationWindows: [],
      urgencyLevels: [],
      destinationEmail: null,
      intakeEmail: 'firma-1@intake.legal-gate.co',
      routingRules: [],
      lawyers: [],
    });
    fixture.detectChanges();

    expect(fixture.componentInstance.view()).toBe('console');
    expect(fixture.componentInstance.tenantId()).toBe('firma-1');
  });

  it('routes an organization without entitlement to billing before protected APIs', () => {
    user.set({ id: 'user_1', email: 'admin@firma.test' });
    hasOrganization.set(true);
    const fixture = TestBed.createComponent(App);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    http.expectOne('/api/session').flush({
      userId: 'user_1',
      sessionId: 'session_1',
      organizationId: 'org_1',
      tenantId: 'firma-1',
      displayName: 'Firma 1',
      role: 'firm_admin',
    });
    http.expectOne('/api/billing/subscription').flush({
      billingEnabled: true,
      enforcementEnabled: true,
      entitled: false,
      status: 'SUBSCRIPTION_REQUIRED',
      subscription: null,
      payments: [],
      accessEndsAt: null,
      message: 'Choose a plan.',
    });
    http.expectOne('/api/billing/plans').flush([]);
    fixture.detectChanges();

    expect(fixture.componentInstance.view()).toBe('billing');
    http.expectNone('/api/consultations');
    http.expectNone('/api/tenant/settings');
  });
});
