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
      classification: {
        label: 'MANUAL_REVIEW',
        matchedUrgentKeywords: ['audiencia'],
        explanation:
          'Pending LLM classification; plain-language consultation accepted for lawyer review.',
      },
      notifications: {
        emailQueued: true,
        calendarUpdateQueued: true,
        destinationEmail: 'intake@firma.test',
        preferredWindow: 'Jueves 2:00 p. m.',
      },
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
  destinationEmail: 'notificaciones@firma.test',
  intakeEmail: 'consultas@firma.test',
};

describe('App landing to login to consultation inbox flow', () => {
  beforeEach(async () => {
    Element.prototype.scrollIntoView = vi.fn();

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
    expect(compiled.textContent).not.toContain('Lawyer');
    expect(compiled.textContent).not.toContain('Abogado');
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
    expect(compiled.textContent).toContain('consultas@firma.test');
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
    http.expectOne('/api/auth/login').flush(
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

    http.expectOne('https://legal-gate-gateway.onrender.com/api/backend/api/auth/login').flush(
      loginResponse,
    );
    http.expectOne(
      'https://legal-gate-gateway.onrender.com/api/backend/api/admin/tenants/firma-demo/consultations',
    ).flush(demoResponse);
    http.expectOne(
      'https://legal-gate-gateway.onrender.com/api/backend/api/tenants/firma-demo/settings',
    ).flush(demoSettings);
  });

  it('lets an admin configure the main intake email for the active tenant', () => {
    const { fixture, http, compiled } = create();

    fixture.componentInstance.showLogin();
    fixture.componentInstance.loginForm.email = 'admin@firma-demo.test';
    fixture.componentInstance.loginForm.password = 'LegalGateDemo2026!';
    fixture.componentInstance.login();

    http.expectOne('/api/auth/login').flush(loginResponse);
    http.expectOne('/api/admin/tenants/firma-demo/consultations').flush(demoResponse);
    http.expectOne('/api/tenants/firma-demo/settings').flush({
      ...demoSettings,
      intakeEmail: null,
    });
    fixture.detectChanges();

    expect(compiled.textContent).toContain('Configuracion');
    expect(compiled.textContent).toContain('Sin configurar');

    fixture.componentInstance.settingsForm.intakeEmail = 'Consultas@Firma.test ';
    fixture.componentInstance.settingsForm.destinationEmail = 'Notificaciones@Firma.test ';
    fixture.componentInstance.settingsForm.urgentKeywords = 'audiencia, tutela';
    fixture.componentInstance.settingsForm.consultationWindows = 'LUN-VIE 09:00-13:00';
    fixture.componentInstance.saveSettings();

    const settingsRequest = http.expectOne('/api/tenants/firma-demo/settings');
    expect(settingsRequest.request.method).toBe('PUT');
    expect(settingsRequest.request.body).toEqual({
      urgentKeywords: ['audiencia', 'tutela'],
      consultationWindows: ['LUN-VIE 09:00-13:00'],
      destinationEmail: 'notificaciones@firma.test',
      intakeEmail: 'consultas@firma.test',
    });
    settingsRequest.flush({
      ...demoSettings,
      urgentKeywords: ['audiencia', 'tutela'],
      consultationWindows: ['LUN-VIE 09:00-13:00'],
      destinationEmail: 'notificaciones@firma.test',
      intakeEmail: 'consultas@firma.test',
    });
    fixture.detectChanges();

    expect(compiled.textContent).toContain('consultas@firma.test');
  });
});
