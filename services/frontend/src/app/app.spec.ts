import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { App } from './app';
import { ApiConfigService } from './config/api-config.service';

const demoResponse = {
  tenantId: 'firma-demo',
  consultations: [
    {
      id: 'case-001',
      tenantId: 'firma-demo',
      clientName: 'Maria Perez',
      clientEmail: 'maria@example.com',
      summary: 'Tengo una audiencia laboral manana y necesito orientacion urgente.',
      preferredWindow: 'Jueves 2:00 p. m.',
      status: 'RECEIVED',
      urgency: 'URGENT',
      consultationType: 'Laboral',
      assignedLawyerEmail: 'laboral@firma.test',
      classification: {
        label: 'MANUAL_REVIEW',
        matchedUrgentKeywords: ['audiencia'],
        concept: 'Audiencia laboral',
        explanation:
          'Pending LLM classification; plain-language consultation accepted for lawyer review.',
        confidence: null,
      },
      notifications: {
        emailQueued: true,
        calendarUpdateQueued: true,
        destinationEmail: 'intake@firma.test',
        preferredWindow: 'Jueves 2:00 p. m.',
      },
      sourceEventId: null,
      sourceMessageId: null,
      createdAt: '2026-06-03T15:00:00Z',
    },
  ],
};

const loginResponse = {
  email: 'admin@firma-demo.test',
  tenantId: 'firma-demo',
  displayName: 'Firma Demo admin',
  role: 'FIRM_ADMIN',
};

const demoSettings = {
  tenantId: 'firma-demo',
  urgentKeywords: ['audiencia', 'captura'],
  consultationWindows: ['LUN-VIE 09:00-13:00'],
  urgencyLevels: ['NORMAL', 'URGENT'],
  destinationEmail: 'notificaciones@firma.test',
  intakeEmail: 'firma-demo@intake.legal-gate.co',
  lawyers: [
    {
      id: 'lawyer-general',
      displayName: 'Notificaciones',
      email: 'notificaciones@firma.test',
      active: true,
      defaultEventDurationMinutes: 60,
      availabilityWindows: [
        { weekday: 1, startTime: '09:00', endTime: '17:00', timezone: 'America/Bogota' },
      ],
    },
  ],
  routingRules: [
    {
      name: 'Default intake route',
      description: 'Ruta general',
      urgentKeywords: ['audiencia', 'captura'],
      consultationWindows: [],
      urgencyLevels: ['NORMAL', 'URGENT'],
      lawyerId: 'lawyer-general',
      urgencyDefinitions: [
        { name: 'NORMAL', rank: 1, slaDays: 5, active: true },
        { name: 'URGENT', rank: 2, slaDays: 1, active: true },
      ],
      destinationEmail: 'notificaciones@firma.test',
    },
  ],
};

describe('App landing to login to consultation inbox flow', () => {
  beforeEach(async () => {
    Element.prototype.scrollIntoView = vi.fn();
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: vi.fn().mockResolvedValue(undefined),
      },
    });

    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  function create() {
    const fixture = TestBed.createComponent(App);
    const http = TestBed.inject(HttpTestingController);
    const apiConfig = TestBed.inject(ApiConfigService);
    fixture.detectChanges();
    return { fixture, http, apiConfig, compiled: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
  });

  it('keeps the public landing intact and adds register next to the login entry point without exposing credentials', () => {
    const { compiled } = create();

    expect(compiled.querySelector('.landing-shell')).toBeTruthy();
    expect(compiled.querySelector('.intake-demo')).toBeTruthy();
    expect(compiled.querySelector('.footer')).toBeTruthy();
    expect(compiled.textContent).toContain('LegalGate');
    expect(compiled.querySelector('h1')?.textContent).toContain(
      'Convierte cada correo de un cliente en una consulta agendada',
    );
    expect(compiled.textContent).toContain('Agenda una demo');
    const navButtons = Array.from(compiled.querySelectorAll<HTMLButtonElement>('.nav button')).map(
      (button) => button.textContent?.trim(),
    );
    expect(navButtons).toEqual(['Registrarse', 'Ingresar']);
    expect(compiled.textContent).toContain('2026 LegalGate');
    expect(compiled.textContent).not.toContain('admin@firma-demo.test');
    expect(compiled.textContent).not.toContain('LegalGateDemo2026!');
    expect(compiled.textContent).not.toContain('credenciales');
  });

  it('registers a firm owner user, creates a firm tenant, and loads an empty consultations inbox', () => {
    const { fixture, http, compiled } = create();

    fixture.componentInstance.showRegister();
    fixture.detectChanges();
    expect(compiled.querySelector('h1')?.textContent).toContain('Crea tu cuenta en LegalGate');

    fixture.componentInstance.registerForm.email = 'Owner@Barragan-Legal.test';
    fixture.componentInstance.registerForm.password = 'StrongPass2026!';
    fixture.componentInstance.registerForm.firmName = 'Barragan Legal';
    fixture.componentInstance.register();

    const registerRequest = http.expectOne('/api/auth/register');
    expect(registerRequest.request.method).toBe('POST');
    expect(registerRequest.request.body).toEqual({
      email: 'owner@barragan-legal.test',
      password: 'StrongPass2026!',
      firmName: 'Barragan Legal',
    });
    registerRequest.flush(
      {
        email: 'owner@barragan-legal.test',
        tenantId: 'barragan-legal',
        displayName: 'Barragan Legal admin',
        role: 'FIRM_ADMIN',
      },
      { status: 201, statusText: 'Created' },
    );
    http.expectOne('/api/admin/tenants/barragan-legal/consultations').flush({
      tenantId: 'barragan-legal',
      consultations: [],
    });
    http.expectOne('/api/tenants/barragan-legal/settings').flush({
      ...demoSettings,
      tenantId: 'barragan-legal',
      intakeEmail: null,
    });
    fixture.detectChanges();

    expect(compiled.querySelector('.console-shell')).toBeTruthy();
    expect(compiled.textContent).toContain('barragan-legal');
    expect(compiled.textContent).toContain('Sesi');
    expect(compiled.textContent).toContain('Todav');
    expect(compiled.textContent).toContain('Abogado 1');
  });

  it('shows a clean login screen without credential hints and then loads the consultations inbox', () => {
    const { fixture, http, compiled } = create();

    fixture.componentInstance.showLogin();
    fixture.detectChanges();
    expect(compiled.querySelector('h1')?.textContent).toContain('Ingresa a LegalGate');
    expect(compiled.textContent).not.toContain('admin@firma-demo.test');
    expect(compiled.textContent).not.toContain('LegalGateDemo2026!');

    fixture.componentInstance.loginForm.email = 'Admin@Firma-Demo.test ';
    fixture.componentInstance.loginForm.password = 'LegalGateDemo2026!';
    fixture.componentInstance.login();

    const loginRequest = http.expectOne('/api/auth/login');
    expect(loginRequest.request.method).toBe('POST');
    expect(loginRequest.request.body).toEqual({
      email: 'admin@firma-demo.test',
      password: 'LegalGateDemo2026!',
    });
    loginRequest.flush(loginResponse);
    http.expectOne('/api/admin/tenants/firma-demo/consultations').flush(demoResponse);
    http.expectOne('/api/tenants/firma-demo/settings').flush(demoSettings);
    fixture.detectChanges();

    expect(compiled.querySelector('.console-shell')).toBeTruthy();
    expect(compiled.querySelector('h1')?.textContent).toContain('Panel operativo de intake');
    expect(compiled.textContent).toContain('Sesi');
    expect(compiled.textContent).toContain('Maria Perez');
    expect(compiled.textContent).toContain('audiencia');
    expect(compiled.textContent).toContain('Laboral');
    expect(compiled.textContent).toContain('laboral@firma.test');
    expect(compiled.textContent).toContain('Audiencia laboral');
    expect(compiled.textContent).toContain('firma-demo@intake.legal-gate.co');
    expect(compiled.textContent).toContain('1 consultas');
    expect(compiled.textContent).not.toContain('API proxy');
    expect(compiled.textContent).not.toContain('intake-orchestrator local');
    expect(compiled.textContent).not.toContain('Nueva consulta');
    expect(compiled.textContent).not.toContain('Crear caso de prueba');
  });

  it('keeps only one console navigation item active and closes the mobile drawer after selection', () => {
    const { fixture, http, compiled } = create();

    fixture.componentInstance.showLogin();
    fixture.componentInstance.loginForm.email = 'admin@firma-demo.test';
    fixture.componentInstance.loginForm.password = 'LegalGateDemo2026!';
    fixture.componentInstance.login();
    http.expectOne('/api/auth/login').flush(loginResponse);
    http.expectOne('/api/admin/tenants/firma-demo/consultations').flush(demoResponse);
    http.expectOne('/api/tenants/firma-demo/settings').flush(demoSettings);
    fixture.detectChanges();

    const navButtons = () =>
      Array.from(compiled.querySelectorAll<HTMLButtonElement>('.sidebar nav button'));
    expect(
      navButtons()
        .filter((button) => button.classList.contains('is-active'))
        .map((button) => button.textContent?.trim()),
    ).toEqual(['Dashboard']);

    fixture.componentInstance.toggleConsoleMenu();
    fixture.detectChanges();
    expect(compiled.querySelector('.console-shell')?.classList.contains('menu-open')).toBe(true);

    navButtons()
      .find((button) => button.textContent?.includes('Consultas'))
      ?.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.activeConsoleSection()).toBe('consultas');
    expect(fixture.componentInstance.isConsoleMenuOpen()).toBe(false);
    expect(
      navButtons()
        .filter((button) => button.classList.contains('is-active'))
        .map((button) => button.textContent?.trim()),
    ).toEqual(['Consultas']);
  });

  it('keeps users on login when the backend rejects credentials', () => {
    const { fixture, http, compiled } = create();

    fixture.componentInstance.showLogin();
    fixture.componentInstance.loginForm.email = 'wrong@example.com';
    fixture.componentInstance.loginForm.password = 'bad-password';
    fixture.componentInstance.login();
    http
      .expectOne('/api/auth/login')
      .flush(
        { error: 'invalid_credentials', message: 'Email or password is incorrect.' },
        { status: 401, statusText: 'Unauthorized' },
      );
    fixture.detectChanges();

    expect(compiled.querySelector('h1')?.textContent).toContain('Ingresa a LegalGate');
    expect(compiled.textContent).toContain('Credenciales invalidas');
    expect(compiled.textContent).not.toContain('admin@firma-demo.test');
    expect(compiled.textContent).not.toContain('LegalGateDemo2026!');
  });

  it('prefixes backend API calls with the configured gateway facade URL', () => {
    const { fixture, http, apiConfig } = create();
    apiConfig.setApiBaseUrl('https://legal-gate-gateway.onrender.com/api/backend/');

    fixture.componentInstance.showLogin();
    fixture.componentInstance.loginForm.email = 'admin@firma-demo.test';
    fixture.componentInstance.loginForm.password = 'LegalGateDemo2026!';
    fixture.componentInstance.login();

    http
      .expectOne('https://legal-gate-gateway.onrender.com/api/backend/api/auth/login')
      .flush(loginResponse);
    http
      .expectOne(
        'https://legal-gate-gateway.onrender.com/api/backend/api/admin/tenants/firma-demo/consultations',
      )
      .flush(demoResponse);
    http
      .expectOne(
        'https://legal-gate-gateway.onrender.com/api/backend/api/tenants/firma-demo/settings',
      )
      .flush(demoSettings);
  });

  it('shows the read-only LegalGate intake email and saves routing rules without intakeEmail', () => {
    const { fixture, http, compiled } = create();

    fixture.componentInstance.showLogin();
    fixture.componentInstance.loginForm.email = 'admin@firma-demo.test';
    fixture.componentInstance.loginForm.password = 'LegalGateDemo2026!';
    fixture.componentInstance.login();

    http.expectOne('/api/auth/login').flush(loginResponse);
    http.expectOne('/api/admin/tenants/firma-demo/consultations').flush(demoResponse);
    http.expectOne('/api/tenants/firma-demo/settings').flush(demoSettings);
    fixture.detectChanges();

    expect(compiled.textContent).toContain('Configuracion');
    expect(compiled.textContent).toContain('Email LegalGate de intake');
    expect(compiled.querySelector('#urgencyLevels')).toBeFalsy();
    expect(compiled.textContent).toContain('firma-demo@intake.legal-gate.co');

    fixture.componentInstance.settingsForm.lawyers[0].displayName = 'Notificaciones';
    fixture.componentInstance.settingsForm.lawyers[0].email = 'notificaciones@firma.test';
    fixture.componentInstance.settingsForm.lawyers[0].active = false;
    fixture.componentInstance.settingsForm.lawyers[0].availabilityWindows[0].timezone = 'UTC';
    fixture.componentInstance.settingsForm.routingRules[0].destinationEmail =
      'notificaciones@firma.test';
    fixture.componentInstance.settingsForm.routingRules[0].urgentKeywords = 'audiencia, tutela';
    fixture.componentInstance.settingsForm.routingRules[0].consultationWindows =
      'LUN-VIE 09:00-13:00';
    fixture.componentInstance.settingsForm.routingRules[0].description = ' Ruta general ';
    fixture.componentInstance.settingsForm.routingRules[0].urgencyDefinitions = [
      { name: 'NORMAL', rank: 10, slaDays: 5, active: false },
      { name: 'URGENT', rank: 4, slaDays: 1, active: false },
      { name: 'CRITICA', rank: 99, slaDays: 5, active: false },
    ];
    fixture.componentInstance.addLawyer();
    fixture.componentInstance.settingsForm.lawyers[1].displayName = 'Laboral';
    fixture.componentInstance.settingsForm.lawyers[1].email = 'laboral@firma.test';
    fixture.componentInstance.settingsForm.lawyers[1].active = false;
    fixture.componentInstance.settingsForm.lawyers[1].availabilityWindows[0].timezone =
      'Europe/Madrid';
    fixture.componentInstance.addRoutingRule();
    fixture.componentInstance.settingsForm.routingRules[1].lawyerId =
      fixture.componentInstance.settingsForm.lawyers[1].id;
    fixture.componentInstance.settingsForm.routingRules[1].name = 'Labor penalties';
    fixture.componentInstance.settingsForm.routingRules[1].description = 'Labor escalations';
    fixture.componentInstance.settingsForm.routingRules[1].destinationEmail = 'Laboral@Firma.test ';
    fixture.componentInstance.settingsForm.routingRules[1].urgentKeywords = 'penalties';
    fixture.componentInstance.settingsForm.routingRules[1].consultationWindows =
      'MAR 09:00-12:00, JUE 09:00-12:00';
    fixture.componentInstance.settingsForm.routingRules[1].urgencyDefinitions = [
      { name: 'URGENT', rank: 9, slaDays: 1, active: false },
      { name: 'CRITICAL', rank: 3, slaDays: 5, active: false },
    ];
    fixture.componentInstance.saveSettings();

    const settingsRequest = http.expectOne('/api/tenants/firma-demo/settings');
    expect(settingsRequest.request.method).toBe('PUT');
    expect(settingsRequest.request.body.lawyers).toHaveLength(2);
    expect(settingsRequest.request.body.lawyers[0]).toMatchObject({
      displayName: 'Notificaciones',
      email: 'notificaciones@firma.test',
      active: true,
    });
    expect(settingsRequest.request.body.lawyers[0].availabilityWindows[0]).toMatchObject({
      weekday: 1,
      startTime: '09:00',
      endTime: '17:00',
      timezone: 'America/Bogota',
    });
    expect(settingsRequest.request.body.lawyers[1]).toMatchObject({
      displayName: 'Laboral',
      email: 'laboral@firma.test',
      active: true,
    });
    expect(settingsRequest.request.body.lawyers[1].availabilityWindows[0].timezone).toBe(
      'America/Bogota',
    );
    expect(settingsRequest.request.body.routingRules[0]).toMatchObject({
      name: 'Default intake route',
      description: 'Ruta general',
      urgentKeywords: ['audiencia', 'tutela'],
      consultationWindows: [],
      urgencyLevels: ['NORMAL', 'URGENT', 'CRITICA'],
      lawyerId: 'lawyer-general',
      destinationEmail: 'notificaciones@firma.test',
    });
    expect(settingsRequest.request.body.routingRules[0].urgencyDefinitions).toEqual([
      { name: 'NORMAL', rank: 1, slaDays: 5, active: true },
      { name: 'URGENT', rank: 2, slaDays: 1, active: true },
      { name: 'CRITICA', rank: 3, slaDays: 5, active: true },
    ]);
    expect(settingsRequest.request.body.routingRules[1]).toMatchObject({
      name: 'Labor penalties',
      description: 'Labor escalations',
      urgentKeywords: ['penalties'],
      consultationWindows: [],
      urgencyLevels: ['URGENT', 'CRITICAL'],
      lawyerId: fixture.componentInstance.settingsForm.lawyers[1].id,
      destinationEmail: 'laboral@firma.test',
    });
    expect(settingsRequest.request.body.routingRules[1].urgencyDefinitions).toEqual([
      { name: 'URGENT', rank: 1, slaDays: 1, active: true },
      { name: 'CRITICAL', rank: 2, slaDays: 5, active: true },
    ]);
    settingsRequest.flush({
      ...demoSettings,
      urgentKeywords: ['audiencia', 'tutela'],
      consultationWindows: ['LUN-VIE 09:00-13:00'],
      urgencyLevels: ['NORMAL', 'URGENT', 'CRITICA', 'CRITICAL'],
      destinationEmail: 'notificaciones@firma.test',
      intakeEmail: 'firma-demo@intake.legal-gate.co',
      lawyers: settingsRequest.request.body.lawyers,
      routingRules: settingsRequest.request.body.routingRules,
    });
    fixture.detectChanges();

    expect(compiled.textContent).toContain('firma-demo@intake.legal-gate.co');
  });

  it('shows one lawyer and one rule card at a time with next and dotted add rails', () => {
    const { fixture, http, compiled } = create();

    fixture.componentInstance.showLogin();
    fixture.componentInstance.loginForm.email = 'admin@firma-demo.test';
    fixture.componentInstance.loginForm.password = 'LegalGateDemo2026!';
    fixture.componentInstance.login();

    http.expectOne('/api/auth/login').flush(loginResponse);
    http.expectOne('/api/admin/tenants/firma-demo/consultations').flush(demoResponse);
    http.expectOne('/api/tenants/firma-demo/settings').flush(demoSettings);

    fixture.componentInstance.addLawyer();
    fixture.componentInstance.settingsForm.lawyers[1].displayName = 'Laboral';
    fixture.componentInstance.settingsForm.lawyers[1].email = 'laboral@firma.test';
    fixture.componentInstance.addRoutingRule();
    fixture.componentInstance.settingsForm.routingRules[1].name = 'Labor route';
    fixture.componentInstance.activeLawyerIndex.set(0);
    fixture.componentInstance.activeRuleIndex.set(0);
    fixture.detectChanges();

    const settingsPanel = compiled.querySelector('#configuracion') as HTMLElement;
    expect(settingsPanel.querySelectorAll('.lawyer-card')).toHaveLength(1);
    expect(settingsPanel.querySelectorAll('.routing-rule')).toHaveLength(1);
    expect(settingsPanel.textContent).toContain('Abogado 1');
    expect(settingsPanel.textContent).not.toContain('Abogado 2');
    expect(settingsPanel.textContent).toContain('Regla 1');
    expect(settingsPanel.textContent).not.toContain('Regla 2');

    const nextLawyerRail = settingsPanel.querySelector<HTMLButtonElement>(
      '[aria-label="Abogado siguiente"]',
    );
    const nextRuleRail = settingsPanel.querySelector<HTMLButtonElement>(
      '[aria-label="Regla siguiente"]',
    );
    expect(nextLawyerRail).toBeTruthy();
    expect(nextLawyerRail?.classList.contains('is-add')).toBe(false);
    expect(nextRuleRail).toBeTruthy();
    expect(nextRuleRail?.classList.contains('is-add')).toBe(false);

    nextLawyerRail?.click();
    nextRuleRail?.click();
    fixture.detectChanges();

    expect(settingsPanel.querySelectorAll('.lawyer-card')).toHaveLength(1);
    expect(settingsPanel.querySelectorAll('.routing-rule')).toHaveLength(1);
    expect(settingsPanel.textContent).toContain('Abogado 2');
    expect(settingsPanel.textContent).toContain('Regla 2');
    expect(
      settingsPanel.querySelector('[aria-label="Agregar abogado"]')?.classList.contains('is-add'),
    ).toBe(true);
    expect(
      settingsPanel.querySelector('[aria-label="Agregar regla"]')?.classList.contains('is-add'),
    ).toBe(true);
  });

  it('hides backend-only configuration controls from the settings panel', () => {
    const { fixture, http, compiled } = create();

    fixture.componentInstance.showLogin();
    fixture.componentInstance.loginForm.email = 'admin@firma-demo.test';
    fixture.componentInstance.loginForm.password = 'LegalGateDemo2026!';
    fixture.componentInstance.login();

    http.expectOne('/api/auth/login').flush(loginResponse);
    http.expectOne('/api/admin/tenants/firma-demo/consultations').flush(demoResponse);
    http.expectOne('/api/tenants/firma-demo/settings').flush(demoSettings);
    fixture.detectChanges();

    const settingsPanel = compiled.querySelector('#configuracion') as HTMLElement;
    expect(settingsPanel.textContent).not.toContain('Activo');
    expect(settingsPanel.textContent).not.toContain('Activa');
    expect(settingsPanel.textContent).not.toContain('Ranking');
    expect(settingsPanel.textContent).not.toContain('America/Bogota');
    expect(settingsPanel.textContent).not.toContain('Ventanas heredadas');
    expect(settingsPanel.querySelector('[name^="lawyerActive-"]')).toBeFalsy();
    expect(settingsPanel.querySelector('[name^="zone-"]')).toBeFalsy();
    expect(settingsPanel.querySelector('[name^="urgencyRank-"]')).toBeFalsy();
    expect(settingsPanel.querySelector('[name^="urgencyActive-"]')).toBeFalsy();
    expect(settingsPanel.querySelector('[id^="consultationWindows-"]')).toBeFalsy();
  });

  it('shows configuration validation errors inside the configuration panel', () => {
    const { fixture, http, compiled } = create();

    fixture.componentInstance.showLogin();
    fixture.componentInstance.loginForm.email = 'admin@firma-demo.test';
    fixture.componentInstance.loginForm.password = 'LegalGateDemo2026!';
    fixture.componentInstance.login();

    http.expectOne('/api/auth/login').flush(loginResponse);
    http.expectOne('/api/admin/tenants/firma-demo/consultations').flush(demoResponse);
    http.expectOne('/api/tenants/firma-demo/settings').flush(demoSettings);
    fixture.detectChanges();

    fixture.componentInstance.settingsForm.lawyers[0].email = 'bad-email';
    fixture.componentInstance.saveSettings();
    fixture.detectChanges();

    const settingsPanel = compiled.querySelector('#configuracion');
    const casesPanel = compiled.querySelector('#consultas');
    expect(settingsPanel?.textContent).toContain(
      'Configura al menos un abogado activo con nombre y email valido',
    );
    expect(casesPanel?.textContent).not.toContain(
      'Configura al menos un abogado activo con nombre y email valido',
    );
    expect(compiled.querySelector('.workspace > .status-banner.has-error')).toBeFalsy();
  });

  it('shows validation errors for invalid per-rule urgency lists', () => {
    const { fixture, http, compiled } = create();

    fixture.componentInstance.showLogin();
    fixture.componentInstance.loginForm.email = 'admin@firma-demo.test';
    fixture.componentInstance.loginForm.password = 'LegalGateDemo2026!';
    fixture.componentInstance.login();

    http.expectOne('/api/auth/login').flush(loginResponse);
    http.expectOne('/api/admin/tenants/firma-demo/consultations').flush(demoResponse);
    http.expectOne('/api/tenants/firma-demo/settings').flush(demoSettings);
    fixture.detectChanges();

    fixture.componentInstance.settingsForm.routingRules[0].urgencyDefinitions = [
      { name: 'NORMAL', rank: 1, slaDays: 5, active: true },
      { name: 'NORMAL', rank: 2, slaDays: 1, active: true },
    ];
    fixture.componentInstance.saveSettings();
    fixture.detectChanges();

    expect(compiled.querySelector('#configuracion')?.textContent).toContain(
      'Configura niveles de urgencia por regla sin vacios ni duplicados',
    );
  });

  it('saves directly edited urgency definitions in row order without legacy urgencyLevels', () => {
    const { fixture, http } = create();

    fixture.componentInstance.showLogin();
    fixture.componentInstance.loginForm.email = 'admin@firma-demo.test';
    fixture.componentInstance.loginForm.password = 'LegalGateDemo2026!';
    fixture.componentInstance.login();

    http.expectOne('/api/auth/login').flush(loginResponse);
    http.expectOne('/api/admin/tenants/firma-demo/consultations').flush(demoResponse);
    http.expectOne('/api/tenants/firma-demo/settings').flush(demoSettings);

    fixture.componentInstance.settingsForm.routingRules[0].urgencyLevels = 'NORMAL, URGENT';
    fixture.componentInstance.settingsForm.routingRules[0].urgencyDefinitions = [
      { name: 'BAJA', rank: 10, slaDays: 7, active: false },
      { name: 'ALTA', rank: 5, slaDays: 1, active: false },
    ];
    fixture.componentInstance.saveSettings();

    const settingsRequest = http.expectOne('/api/tenants/firma-demo/settings');
    expect(settingsRequest.request.body.routingRules[0].urgencyDefinitions).toEqual([
      { name: 'BAJA', rank: 1, slaDays: 7, active: true },
      { name: 'ALTA', rank: 2, slaDays: 1, active: true },
    ]);
    expect(settingsRequest.request.body.routingRules[0].urgencyLevels).toEqual(['BAJA', 'ALTA']);
    settingsRequest.flush(demoSettings);
  });

  it('copies the read-only intake email with temporary feedback', async () => {
    const { fixture, http, compiled } = create();

    fixture.componentInstance.showLogin();
    fixture.componentInstance.loginForm.email = 'admin@firma-demo.test';
    fixture.componentInstance.loginForm.password = 'LegalGateDemo2026!';
    fixture.componentInstance.login();

    http.expectOne('/api/auth/login').flush(loginResponse);
    http.expectOne('/api/admin/tenants/firma-demo/consultations').flush(demoResponse);
    http.expectOne('/api/tenants/firma-demo/settings').flush(demoSettings);
    fixture.detectChanges();

    fixture.componentInstance.copyIntakeEmail();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(vi.mocked(navigator.clipboard.writeText)).toHaveBeenCalledWith(
      'firma-demo@intake.legal-gate.co',
    );
    expect(compiled.textContent).toContain('Copiado');
  });

  it('opens the forwarding tutorial modal and closes it by button, Escape, and backdrop', () => {
    const { fixture, http, compiled } = create();

    fixture.componentInstance.showLogin();
    fixture.componentInstance.loginForm.email = 'admin@firma-demo.test';
    fixture.componentInstance.loginForm.password = 'LegalGateDemo2026!';
    fixture.componentInstance.login();

    http.expectOne('/api/auth/login').flush(loginResponse);
    http.expectOne('/api/admin/tenants/firma-demo/consultations').flush(demoResponse);
    http.expectOne('/api/tenants/firma-demo/settings').flush(demoSettings);
    fixture.detectChanges();

    fixture.componentInstance.openTutorial();
    fixture.detectChanges();
    expect(compiled.querySelector('[role="dialog"]')?.textContent).toContain('Reenviar o enrutar');
    expect(compiled.querySelector('[role="dialog"]')?.textContent).toContain(
      'firma-demo@intake.legal-gate.co',
    );

    compiled.querySelector<HTMLButtonElement>('[aria-label="Cerrar tutorial"]')?.click();
    fixture.detectChanges();
    expect(compiled.querySelector('[role="dialog"]')).toBeFalsy();

    fixture.componentInstance.openTutorial();
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(compiled.querySelector('[role="dialog"]')).toBeFalsy();

    fixture.componentInstance.openTutorial();
    fixture.detectChanges();
    compiled.querySelector<HTMLDivElement>('.modal-backdrop')?.click();
    fixture.detectChanges();
    expect(compiled.querySelector('[role="dialog"]')).toBeFalsy();
  });

  it('shows consultation inbox errors inside the consultations panel', () => {
    const { fixture, http, compiled } = create();

    fixture.componentInstance.showLogin();
    fixture.componentInstance.loginForm.email = 'admin@firma-demo.test';
    fixture.componentInstance.loginForm.password = 'LegalGateDemo2026!';
    fixture.componentInstance.login();

    http.expectOne('/api/auth/login').flush(loginResponse);
    http
      .expectOne('/api/admin/tenants/firma-demo/consultations')
      .flush({ error: 'service_unavailable' }, { status: 503, statusText: 'Unavailable' });
    http.expectOne('/api/tenants/firma-demo/settings').flush(demoSettings);
    fixture.detectChanges();

    const casesPanel = compiled.querySelector('#consultas');
    const settingsPanel = compiled.querySelector('#configuracion');
    expect(casesPanel?.textContent).toContain('No se pudo conectar con el Sistema');
    expect(settingsPanel?.textContent).not.toContain('No se pudo conectar con el Sistema');
    expect(compiled.querySelector('.workspace > .status-banner.has-error')).toBeFalsy();
  });
});
