import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { App } from './app';

const demoResponse = {
  tenantId: 'firma-demo',
  consultations: [
    {
      id: 'case-001',
      tenantId: 'firma-demo',
      clientName: 'María Pérez',
      clientEmail: 'maria@example.com',
      summary: 'Tengo una audiencia laboral mañana y necesito orientación urgente.',
      preferredWindow: 'Jueves 2:00 p. m.',
      status: 'RECEIVED',
      urgency: 'URGENT',
      classification: {
        label: 'MANUAL_REVIEW',
        matchedUrgentKeywords: ['audiencia'],
        explanation: 'Pending LLM classification; plain-language consultation accepted for lawyer review.',
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

describe('App landing to login to consultation inbox flow', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  function create() {
    const fixture = TestBed.createComponent(App);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    return { fixture, http, compiled: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
  });

  it('keeps the public landing intact and only adds the login entry point without exposing credentials', () => {
    const { compiled } = create();

    expect(compiled.querySelector('.landing-shell')).toBeTruthy();
    expect(compiled.querySelector('.intake-demo')).toBeTruthy();
    expect(compiled.querySelector('.footer')).toBeTruthy();
    expect(compiled.textContent).toContain('LegalGate');
    expect(compiled.querySelector('h1')?.textContent).toContain(
      'Convierte cada correo de un cliente en una consulta agendada',
    );
    expect(compiled.textContent).toContain('Agenda una demo');
    expect(compiled.textContent).toContain('Ingresar');
    expect(compiled.textContent).toContain('© 2026 LegalGate · INTAKE · ROUTING · AUDIT');
    expect(compiled.textContent).not.toContain('admin@firma-demo.test');
    expect(compiled.textContent).not.toContain('LegalGateDemo2026!');
    expect(compiled.textContent).not.toContain('credenciales');
  });

  it('shows a clean login screen without credential hints and then loads the consultations inbox', () => {
    const { fixture, http, compiled } = create();

    fixture.componentInstance.showLogin();
    fixture.detectChanges();
    expect(compiled.querySelector('h1')?.textContent).toContain('Ingresa a LegalGate');
    expect(compiled.textContent).not.toContain('admin@firma-demo.test');
    expect(compiled.textContent).not.toContain('LegalGateDemo2026!');

    fixture.componentInstance.loginForm.email = 'admin@firma-demo.test';
    fixture.componentInstance.loginForm.password = 'LegalGateDemo2026!';
    fixture.componentInstance.login();

    http.expectOne('/api/admin/tenants/firma-demo/consultations').flush(demoResponse);
    fixture.detectChanges();

    expect(compiled.querySelector('.console-shell')).toBeTruthy();
    expect(compiled.querySelector('h1')?.textContent).toContain('Panel operativo de intake');
    expect(compiled.textContent).toContain('Sesión admin');
    expect(compiled.textContent).toContain('María Pérez');
    expect(compiled.textContent).toContain('audiencia');
    expect(compiled.textContent).toContain('1 consultas');
    expect(compiled.textContent).not.toContain('API proxy');
    expect(compiled.textContent).not.toContain('intake-orchestrator local');
    expect(compiled.textContent).not.toContain('Nueva consulta');
    expect(compiled.textContent).not.toContain('Crear caso de prueba');
  });

  it('keeps users on login when credentials are invalid', () => {
    const { fixture, compiled } = create();

    fixture.componentInstance.showLogin();
    fixture.componentInstance.loginForm.email = 'wrong@example.com';
    fixture.componentInstance.loginForm.password = 'bad-password';
    fixture.componentInstance.login();
    fixture.detectChanges();

    expect(compiled.querySelector('h1')?.textContent).toContain('Ingresa a LegalGate');
    expect(compiled.textContent).toContain('Credenciales inválidas');
    expect(compiled.textContent).not.toContain('admin@firma-demo.test');
    expect(compiled.textContent).not.toContain('LegalGateDemo2026!');
  });
});
