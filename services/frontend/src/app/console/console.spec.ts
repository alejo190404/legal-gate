import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AuthService } from '../auth/auth.service';
import { ApiConfigService } from '../config/api-config.service';
import { ConsoleComponent } from './console';

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

  it('names the Clients and Activities a lawyer is still in charge of when a save would remove them', () => {
    user.set({ id: 'user_1', email: 'admin@firma.test' });
    hasOrganization.set(true);
    const fixture = TestBed.createComponent(ConsoleComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/session').flush({
      userId: 'user_1', sessionId: 'session_1', organizationId: 'org_1', tenantId: 'firma-1',
      displayName: 'Firma 1', role: 'firm_admin',
    });
    http.expectOne('/api/billing/subscription').flush({
      billingEnabled: false, enforcementEnabled: false, entitled: true, status: 'DISABLED',
      subscription: null, payments: [], accessEndsAt: null, message: '',
    });
    http.expectOne('/api/consultations').flush({ tenantId: 'firma-1', consultations: [] });
    http.expectOne('/api/tenant/settings').flush({
      tenantId: 'firma-1', urgentKeywords: [], consultationWindows: [], urgencyLevels: ['NORMAL'],
      destinationEmail: 'ana@firm.co', intakeEmail: 'firma-1@intake.legal-gate.co',
      routingRules: [{
        name: 'Laboral', description: null, urgentKeywords: [], consultationWindows: [], urgencyLevels: ['NORMAL'],
        lawyerId: 'lawyer-ana', urgencyDefinitions: [{ name: 'NORMAL', rank: 1, slaDays: 5, active: true }],
        destinationEmail: 'ana@firm.co',
      }],
      lawyers: [{
        id: 'lawyer-ana', displayName: 'Ana Abogada', email: 'ana@firm.co', meetingUrl: null, active: true,
        defaultEventDurationMinutes: 60,
        availabilityWindows: [{ weekday: 1, startTime: '09:00', endTime: '17:00', timezone: 'America/Bogota' }],
      }],
      diagnosticsPrompt: null, nonEngagementNotice: null,
    });
    fixture.detectChanges();

    fixture.componentInstance.saveSettings();
    http.expectOne('/api/tenant/settings').flush(
      {
        error: 'lawyer_still_in_charge',
        message: 'Ana Abogada sigue a cargo de: cliente Acme SAS. Reasígnalos antes de desactivarlo o quitarlo.',
      },
      { status: 409, statusText: 'Conflict' },
    );

    expect(fixture.componentInstance.settingsErrorMessage()).toBe(
      'Ana Abogada sigue a cargo de: cliente Acme SAS. Reasígnalos antes de desactivarlo o quitarlo.',
    );
  });

  function consoleWith(lawyers: unknown[]) {
    user.set({ id: 'user_1', email: 'admin@firma.test' });
    hasOrganization.set(true);
    const fixture = TestBed.createComponent(ConsoleComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/session').flush({
      userId: 'user_1', sessionId: 'session_1', organizationId: 'org_1', tenantId: 'firma-1',
      displayName: 'Firma 1', role: 'firm_admin',
    });
    http.expectOne('/api/billing/subscription').flush({
      billingEnabled: false, enforcementEnabled: false, entitled: true, status: 'DISABLED',
      subscription: null, payments: [], accessEndsAt: null, message: '',
    });
    http.expectOne('/api/consultations').flush({ tenantId: 'firma-1', consultations: [] });
    http.expectOne('/api/tenant/settings').flush({
      tenantId: 'firma-1', urgentKeywords: [], consultationWindows: [], urgencyLevels: ['NORMAL'],
      destinationEmail: 'ana@firm.co', intakeEmail: 'firma-1@intake.legal-gate.co',
      routingRules: [{
        name: 'Laboral', description: null, urgentKeywords: [], consultationWindows: [], urgencyLevels: ['NORMAL'],
        lawyerId: 'lawyer-ana', urgencyDefinitions: [{ name: 'NORMAL', rank: 1, slaDays: 5, active: true }],
        destinationEmail: 'ana@firm.co',
      }],
      lawyers,
      diagnosticsPrompt: null, nonEngagementNotice: null,
    });
    return { fixture, component: fixture.componentInstance, http };
  }

  const lawyer = (id: string, displayName: string, active: boolean) => ({
    id, displayName, email: `${id}@firm.co`, meetingUrl: null, active, defaultEventDurationMinutes: 60,
    availabilityWindows: [{ weekday: 1, startTime: '09:00', endTime: '17:00', timezone: 'America/Bogota' }],
  });

  it('says who already has an email, or that two lawyers share one, when the team save is refused', () => {
    const { component, http } = consoleWith([lawyer('lawyer-ana', 'Ana Abogada', true)]);

    component.saveSettings();
    http.expectOne('/api/tenant/settings').flush(
      { error: 'lawyer_email_taken', message: 'rita@firm.co ya pertenece a Rita Retirada.' },
      { status: 400, statusText: 'Bad Request' },
    );
    expect(component.settingsErrorMessage()).toBe('rita@firm.co ya pertenece a Rita Retirada.');

    component.saveSettings();
    http.expectOne('/api/tenant/settings').flush(
      { error: 'duplicate_lawyer_email', message: 'duplicate_lawyer_email' },
      { status: 400, statusText: 'Bad Request' },
    );
    expect(component.settingsErrorMessage()).toBe('Dos abogados no pueden tener el mismo email.');
  });

  it('opens the Client and Activity a reminder email links to once the session loads', () => {
    window.history.replaceState({}, '', '/dashboard?client=client-1&activity=activity-1');
    try {
      const { component } = consoleWith([lawyer('lawyer-ana', 'Ana Abogada', true)]);

      expect(component.activeView()).toBe('clients');
      expect(component.openClientId()).toBe('client-1');
      expect(component.openActivityId()).toBe('activity-1');
    } finally {
      window.history.replaceState({}, '', '/');
    }
  });

  it('falls back to the inbox when a reminder link names a Client the firm does not have', () => {
    window.history.replaceState({}, '', '/dashboard?client=client-gone&activity=activity-1');
    try {
      const { fixture, component, http } = consoleWith([lawyer('lawyer-ana', 'Ana Abogada', true)]);
      fixture.detectChanges();
      http.expectOne('/api/activity-types').flush([]);
      http.expectOne('/api/clients').flush([]);
      fixture.detectChanges();

      expect(component.activeView()).toBe('inbox');
      expect(component.openClientId()).toBeNull();
      expect(component.openActivityId()).toBeNull();
    } finally {
      window.history.replaceState({}, '', '/');
    }
  });

  it('shows only active lawyers in Equipo but keeps the ones who left for Client history', () => {
    const { component, http } = consoleWith([
      lawyer('lawyer-ana', 'Ana Abogada', true),
      lawyer('lawyer-rita', 'Rita Retirada', false),
    ]);

    expect(component.activeView()).toBe('inbox');
    expect(component.settingsForm.lawyers.map((form) => form.id)).toEqual(['lawyer-ana']);
    expect(component.savedLawyers().map((saved) => saved.id)).toEqual(['lawyer-ana', 'lawyer-rita']);

    component.saveSettings();

    expect(http.expectOne('/api/tenant/settings').request.body.lawyers.map((sent: { id: string }) => sent.id))
      .toEqual(['lawyer-ana']);
  });

  it('sends the automatic-answers switch with the settings', () => {
    user.set({ id: 'user_1', email: 'admin@firma.test' });
    hasOrganization.set(true);
    const fixture = TestBed.createComponent(ConsoleComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/session').flush({
      userId: 'user_1', sessionId: 'session_1', organizationId: 'org_1', tenantId: 'firma-1',
      displayName: 'Firma 1', role: 'firm_admin',
    });
    http.expectOne('/api/billing/subscription').flush({
      billingEnabled: false, enforcementEnabled: false, entitled: true, status: 'DISABLED',
      subscription: null, payments: [], accessEndsAt: null, message: '',
    });
    http.expectOne('/api/consultations').flush({ tenantId: 'firma-1', consultations: [] });
    http.expectOne('/api/tenant/settings').flush({
      tenantId: 'firma-1', urgentKeywords: [], consultationWindows: [], urgencyLevels: ['NORMAL'],
      destinationEmail: 'ana@firm.co', intakeEmail: 'firma-1@intake.legal-gate.co',
      routingRules: [{
        name: 'Laboral', description: null, urgentKeywords: [], consultationWindows: [], urgencyLevels: ['NORMAL'],
        lawyerId: 'lawyer-ana', urgencyDefinitions: [{ name: 'NORMAL', rank: 1, slaDays: 5, active: true }],
        destinationEmail: 'ana@firm.co',
      }],
      lawyers: [{
        id: 'lawyer-ana', displayName: 'Ana Abogada', email: 'ana@firm.co', meetingUrl: null, active: true,
        defaultEventDurationMinutes: 60,
        availabilityWindows: [{ weekday: 1, startTime: '09:00', endTime: '17:00', timezone: 'America/Bogota' }],
      }],
      diagnosticsPrompt: null, nonEngagementNotice: null, automaticAnswers: false,
    });
    fixture.detectChanges();
    expect(fixture.componentInstance.settingsForm.automaticAnswers).toBe(false);

    fixture.componentInstance.settingsForm.automaticAnswers = true;
    fixture.componentInstance.saveSettings();

    expect(http.expectOne('/api/tenant/settings').request.body.automaticAnswers).toBe(true);
  });

  describe('Abogados and Reglas pages', () => {
    const rule = (name: string) => ({
      name, description: null, urgentKeywords: [], consultationWindows: [], urgencyLevels: ['NORMAL'],
      lawyerId: 'lawyer-ana', urgencyDefinitions: [{ name: 'NORMAL', rank: 1, slaDays: 5, active: true }],
      destinationEmail: 'ana@firm.co',
    });
    const lawyer = (id: string, displayName: string) => ({
      id, displayName, email: `${id}@firm.co`, meetingUrl: null, active: true, defaultEventDurationMinutes: 60,
      availabilityWindows: [{ weekday: 1, startTime: '09:00', endTime: '17:00', timezone: 'America/Bogota' }],
    });
    const settings = (routingRules: unknown[]) => ({
      tenantId: 'firma-1', urgentKeywords: [], consultationWindows: [], urgencyLevels: ['NORMAL'],
      destinationEmail: 'ana@firm.co', intakeEmail: 'firma-1@intake.legal-gate.co', routingRules,
      lawyers: [lawyer('lawyer-ana', 'Ana Abogada'), lawyer('lawyer-luis', 'Luis Abogado')],
      diagnosticsPrompt: null, nonEngagementNotice: null,
    });

    function loadedConsole(routingRules: unknown[] = [rule('Laboral')]) {
      user.set({ id: 'user_1', email: 'admin@firma.test' });
      hasOrganization.set(true);
      const fixture = TestBed.createComponent(ConsoleComponent);
      const http = TestBed.inject(HttpTestingController);
      fixture.detectChanges();
      http.expectOne('/api/session').flush({
        userId: 'user_1', sessionId: 'session_1', organizationId: 'org_1', tenantId: 'firma-1',
        displayName: 'Firma 1', role: 'firm_admin',
      });
      http.expectOne('/api/billing/subscription').flush({
        billingEnabled: false, enforcementEnabled: false, entitled: true, status: 'DISABLED',
        subscription: null, payments: [], accessEndsAt: null, message: '',
      });
      http.expectOne('/api/consultations').flush({ tenantId: 'firma-1', consultations: [] });
      http.expectOne('/api/tenant/settings').flush(settings(routingRules));
      fixture.detectChanges();
      const component = fixture.componentInstance;
      const text = (view: 'team' | 'rules') => {
        component.activeView.set(view);
        fixture.detectChanges();
        return (fixture.nativeElement as HTMLElement).textContent!;
      };
      return { fixture, http, component, text };
    }

    it('shows unsaved changes until a save succeeds, and keeps them when the save is rejected', () => {
      const { http, component, text } = loadedConsole();
      expect(text('team')).not.toContain('Cambios sin guardar');
      expect(text('rules')).not.toContain('Cambios sin guardar');

      component.removeLawyer(1);
      expect(text('team')).toContain('Cambios sin guardar');

      component.saveSettings();
      http.expectOne('/api/tenant/settings').flush(
        { error: 'lawyer_still_in_charge', message: 'Luis Abogado sigue a cargo de: cliente Acme SAS.' },
        { status: 409, statusText: 'Conflict' },
      );
      expect(text('team')).toContain('Cambios sin guardar');
      expect(text('team')).toContain('Luis Abogado sigue a cargo de: cliente Acme SAS.');

      component.saveSettings();
      const saved = http.expectOne('/api/tenant/settings');
      saved.flush({ ...settings(saved.request.body.routingRules), lawyers: saved.request.body.lawyers });
      expect(text('team')).not.toContain('Cambios sin guardar');
    });

    it('shows a lawyer edit only on Abogados and a rule edit only on Reglas, and a save clears both', () => {
      const { http, component, text } = loadedConsole();

      component.settingsForm.lawyers[1].displayName = 'Luis Editado';
      expect(text('team')).toContain('Cambios sin guardar');
      expect(text('rules')).not.toContain('Cambios sin guardar');

      component.settingsForm.lawyers[1].displayName = 'Luis Abogado';
      component.addRoutingRule();
      expect(text('rules')).toContain('Cambios sin guardar');
      expect(text('team')).not.toContain('Cambios sin guardar');

      component.settingsForm.lawyers[1].displayName = 'Luis Editado';
      component.saveSettings();
      const saved = http.expectOne('/api/tenant/settings');
      saved.flush({ ...settings(saved.request.body.routingRules), lawyers: saved.request.body.lawyers });
      expect(text('team')).not.toContain('Cambios sin guardar');
      expect(text('rules')).not.toContain('Cambios sin guardar');
    });

    it('shows neither page as changed for a Diagnóstico edit, and both when removing a lawyer reassigns rules', () => {
      const { component, text } = loadedConsole();

      component.settingsForm.diagnosticsPrompt = 'Pregunte por la fecha del despido.';
      component.settingsForm.automaticAnswers = true;
      expect(text('team')).not.toContain('Cambios sin guardar');
      expect(text('rules')).not.toContain('Cambios sin guardar');

      component.removeLawyer(0);
      expect(text('team')).toContain('Cambios sin guardar');
      expect(text('rules')).toContain('Cambios sin guardar');
    });

    it('shows unsaved changes when a rule is added, edited or removed', async () => {
      const { fixture, component, text } = loadedConsole();

      component.addRoutingRule();
      expect(text('rules')).toContain('Cambios sin guardar');
      component.removeRoutingRule(1);
      expect(text('rules')).not.toContain('Cambios sin guardar');
      await fixture.whenStable();
      const keywords = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#urgentKeywords-0')!;
      keywords.value = 'tutela';
      keywords.dispatchEvent(new Event('input'));
      expect(text('rules')).toContain('Cambios sin guardar');
    });

    it('names a rule from its card and falls back to "Regla N" when the name is blank', async () => {
      const { fixture, http, component, text } = loadedConsole([rule('Laboral'), rule('')]);
      expect(component.settingsForm.routingRules[1].name).toBe('Regla 2');
      component.addRoutingRule();
      expect(component.settingsForm.routingRules[2].name).toBe('Regla 3');

      text('rules');
      component.activeRuleIndex.set(0);
      fixture.detectChanges();
      await fixture.whenStable();
      const name = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#routeName-0')!;
      expect(name.value).toBe('Laboral');
      name.value = 'Familia';
      name.dispatchEvent(new Event('input'));
      component.settingsForm.routingRules[2].name = '  ';

      component.saveSettings();
      expect(http.expectOne('/api/tenant/settings').request.body.routingRules.map((sent: { name: string }) => sent.name))
        .toEqual(['Familia', 'Regla 2', 'Regla 3']);
    });
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
      status: 'SUSCRIPCION_REQUERIDA',
      subscription: null,
      payments: [],
      accessEndsAt: null,
      message: 'Escoge un plan.',
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
      urgencyLevels: ['NORMAL', 'URGENTE'],
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

  it('activates a 100%-discount coupon without redirecting to Mercado Pago', () => {
    const fixture = TestBed.createComponent(ConsoleComponent);
    const http = TestBed.inject(HttpTestingController);
    const component = fixture.componentInstance;
    component.tenantId.set('tenant-1');
    component.selectedPlanCode.set('monthly');
    component.couponCode.set('FREE100');

    component.checkoutBilling();
    const checkout = http.expectOne('/api/billing/checkout');
    checkout.flush({ status: 'ACTIVE', checkoutUrl: null });

    const status = http.expectOne('/api/billing/subscription');
    status.flush({
      billingEnabled: true,
      enforcementEnabled: true,
      entitled: true,
      status: 'ACTIVE',
      subscription: null,
      payments: [],
      accessEndsAt: null,
      message: 'Suscripcion activa.',
    });
    http.expectOne('/api/consultations').flush({ tenantId: 'tenant-1', consultations: [] });
    http.expectOne('/api/tenant/settings').flush({
      tenantId: 'tenant-1',
      urgentKeywords: [],
      consultationWindows: [],
      urgencyLevels: [],
      destinationEmail: null,
      intakeEmail: 'tenant-1@intake.legal-gate.co',
      routingRules: [],
      lawyers: [],
    });

    expect(component.billingError()).toBe('');
    expect(component.view()).toBe('console');
  });

  it('sorts Bandeja by the Stage each Consultation reports', () => {
    user.set({ id: 'user_1', email: 'admin@firma.test' });
    hasOrganization.set(true);
    const fixture = TestBed.createComponent(ConsoleComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/session').flush({
      userId: 'user_1', sessionId: 'session_1', organizationId: 'org_1', tenantId: 'firma-1',
      displayName: 'Firma 1', role: 'firm_admin',
    });
    http.expectOne('/api/billing/subscription').flush({
      billingEnabled: false, enforcementEnabled: false, entitled: true, status: 'DISABLED',
      subscription: null, payments: [], accessEndsAt: null, message: '',
    });
    // Nothing in these fields says rejected or scheduled; only the reported Stage does.
    const consultation = (id: string, stage: string) => ({
      id, tenantId: 'firma-1', clientName: 'Ana', clientEmail: 'ana@example.com', summary: 'Contrato',
      preferredWindow: null, status: 'RECEIVED', urgency: 'NORMAL', consultationType: null,
      assignedLawyerEmail: null, classification: null, notifications: null, sourceEventId: null,
      sourceMessageId: null, createdAt: '2026-10-01T10:00:00Z', eventId: null, event: null,
      diagnosticsSummary: null, stage,
    });
    http.expectOne('/api/consultations').flush({
      tenantId: 'firma-1',
      consultations: [consultation('c-1', 'rejected'), consultation('c-2', 'scheduled'), consultation('c-3', 'scheduled')],
    });
    http.expectOne('/api/tenant/settings').flush({
      tenantId: 'firma-1', urgentKeywords: [], consultationWindows: [], urgencyLevels: [],
      destinationEmail: null, intakeEmail: 'firma-1@intake.legal-gate.co', routingRules: [], lawyers: [],
    });
    const component = fixture.componentInstance;

    expect(component.statusCount('all')).toBe(3);
    expect(component.statusCount('scheduled')).toBe(2);
    expect(component.statusCount('rejected')).toBe(1);
    expect(component.statusCount('new')).toBe(0);
    component.inboxFilter.set('rejected');
    expect(component.filteredConsultations().map((c) => c.id)).toEqual(['c-1']);
    expect(component.statusLabel(component.filteredConsultations()[0])).toBe('Rechazado');
    expect(component.badgeClass(component.filteredConsultations()[0])).toBe('badge st-rejected');
  });
});
