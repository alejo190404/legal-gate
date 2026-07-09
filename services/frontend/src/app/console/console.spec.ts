import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { ConsoleComponent } from './console';
import { AuthService } from '../auth/auth.service';
import { ApiConfigService } from '../config/api-config.service';

describe('ConsoleComponent session + billing flow', () => {
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
    sessionStorage.clear();
    user.set(null);
    hasOrganization.set(false);
    vi.clearAllMocks();
    await TestBed.configureTestingModule({
      imports: [ConsoleComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        ApiConfigService,
      ],
    }).compileComponents();
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('restores an organization session and only calls tenant-neutral APIs', () => {
    user.set({ id: 'user_1', email: 'admin@firma.test' });
    hasOrganization.set(true);
    const fixture = TestBed.createComponent(ConsoleComponent);
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

  it('routes an organization without entitlement to onboarding before checkout', () => {
    user.set({ id: 'user_1', email: 'admin@firma.test' });
    hasOrganization.set(true);
    const fixture = TestBed.createComponent(ConsoleComponent);
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
    // Onboarding prefills from settings, which are readable before payment.
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

    expect(fixture.componentInstance.view()).toBe('onboard-lawyers');
    expect(fixture.componentInstance.onboardingLawyers()).toEqual([]);
    expect(fixture.componentInstance.onboardingRules()).toEqual([]);
    http.expectNone('/api/consultations');
    http.expectNone('/api/billing/plans');
  });

  it('walks onboarding to checkout: add lawyer, add rule, save settings', () => {
    const fixture = TestBed.createComponent(ConsoleComponent);
    const http = TestBed.inject(HttpTestingController);
    const component = fixture.componentInstance;
    component.tenantId.set('firma-1');
    component.settingsLoaded.set(true);
    component.view.set('onboard-lawyers');

    component.lawyerDraft.displayName = 'Abogada Uno';
    component.lawyerDraft.email = 'uno@firma.test';
    component.addOnboardingLawyer();
    expect(component.onboardingLawyers().length).toBe(1);
    expect(component.onboardingError()).toBe('');

    component.goToOnboardingRules();
    expect(component.view()).toBe('onboard-rules');

    component.ruleDraft.name = 'Laboral';
    component.saveOnboardingRule();
    expect(component.onboardingRules().length).toBe(1);
    expect(component.onboardingRules()[0].lawyerId).toBe(component.onboardingLawyers()[0].id);

    component.finishOnboarding();
    const put = http.expectOne('/api/tenant/settings');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body.lawyers.length).toBe(1);
    expect(put.request.body.routingRules[0].name).toBe('Laboral');
    expect(put.request.body.routingRules[0].destinationEmail).toBe('uno@firma.test');
    put.flush({
      tenantId: 'firma-1',
      urgentKeywords: [],
      consultationWindows: [],
      urgencyLevels: ['NORMAL', 'URGENT'],
      destinationEmail: 'uno@firma.test',
      intakeEmail: 'firma-1@intake.legal-gate.co',
      routingRules: put.request.body.routingRules,
      lawyers: put.request.body.lawyers,
    });

    expect(component.view()).toBe('billing');
    http.expectOne('/api/billing/plans').flush([]);
  });

  it('caps onboarding at three lawyers', () => {
    const fixture = TestBed.createComponent(ConsoleComponent);
    const component = fixture.componentInstance;
    for (let i = 0; i < 4; i++) {
      component.lawyerDraft.displayName = `Abogada ${i}`;
      component.lawyerDraft.email = `abogada${i}@firma.test`;
      component.addOnboardingLawyer();
    }
    expect(component.onboardingLawyers().length).toBe(3);
    expect(component.onboardingLawyerLimitReached()).toBe(true);
  });

  it('resets a plan selection that is absent from a refreshed catalog', () => {
    const fixture = TestBed.createComponent(ConsoleComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.componentInstance.selectedPlanCode.set('retired');

    fixture.componentInstance.loadBillingPlans();
    http.expectOne('/api/billing/plans').flush([
      {
        id: 'plan-1',
        code: 'monthly',
        version: 2,
        displayName: 'Monthly',
        description: null,
        interval: 'MONTHLY',
        priceCop: 100000,
      },
    ]);

    expect(fixture.componentInstance.selectedPlanCode()).toBe('monthly');
    expect(fixture.componentInstance.billingQuote()).toBeNull();
  });

  it('rotates checkout idempotency after a terminal client error', () => {
    const fixture = TestBed.createComponent(ConsoleComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.componentInstance.tenantId.set('tenant-1');
    fixture.componentInstance.selectedPlanCode.set('monthly');

    fixture.componentInstance.checkoutBilling();
    const first = http.expectOne('/api/billing/checkout');
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush({}, { status: 400, statusText: 'Bad Request' });

    fixture.componentInstance.checkoutBilling();
    const second = http.expectOne('/api/billing/checkout');
    const secondKey = second.request.headers.get('Idempotency-Key');
    second.flush({}, { status: 400, statusText: 'Bad Request' });

    expect(firstKey).toBeTruthy();
    expect(secondKey).toBeTruthy();
    expect(secondKey).not.toBe(firstKey);
  });
});
