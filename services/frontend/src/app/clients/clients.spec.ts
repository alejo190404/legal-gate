import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { ApiConfigService } from '../config/api-config.service';
import { Client, ClientsComponent } from './clients';

const LAWYERS = [
  { id: 'lawyer-ana', displayName: 'Ana Abogada', email: 'ana@firm.co', active: true },
  { id: 'lawyer-rita', displayName: 'Rita Retirada', email: 'rita@firm.co', active: false },
];

const TYPES = [
  { id: 'type-draft', name: 'Redacción de contrato', kind: 'TURNAROUND', defaultTurnaroundDays: 5, defaultTermDays: null, retiredAt: null },
  { id: 'type-ruling', name: 'Fallo de tutela', kind: 'EXTERNAL', defaultTurnaroundDays: null, defaultTermDays: 10, retiredAt: null },
  { id: 'type-old', name: 'Retirado', kind: 'DATED', defaultTurnaroundDays: null, defaultTermDays: null, retiredAt: '2026-01-01T00:00:00Z' },
];

function client(overrides: Partial<Client> = {}): Client {
  return {
    id: 'client-1',
    name: 'Acme SAS',
    kind: 'ORGANIZATION',
    idNumber: '900123456',
    phone: null,
    notes: null,
    responsibleLawyerId: 'lawyer-ana',
    contactAddresses: ['gc@acme.co'],
    openActivities: 0,
    createdAt: '2026-10-01T15:00:00Z',
    updatedAt: '2026-10-01T15:00:00Z',
    ...overrides,
  };
}

describe('ClientsComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ClientsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), ApiConfigService],
    }).compileComponents();
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  function setup() {
    const fixture = TestBed.createComponent(ClientsComponent);
    fixture.componentRef.setInput('lawyers', LAWYERS);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/activity-types').flush(TYPES);
    return { fixture, http, component: fixture.componentInstance };
  }

  it('lists clients and searches and filters them on the server', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush([client()]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Acme SAS');
    expect(fixture.nativeElement.textContent).toContain('gc@acme.co');

    component.search('acme');
    http.expectOne('/api/clients?q=acme').flush([client()]);
    component.toggleIdle();
    http.expectOne('/api/clients?q=acme&idle=true').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No hay clientes en esta vista');
  });

  it('creates a client with only active lawyers offered as responsible', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush([]);

    component.openCreate();
    fixture.detectChanges();
    const options = Array.from(fixture.nativeElement.querySelectorAll('#clientLawyer option')).map(
      (option: any) => option.textContent.trim(),
    );
    expect(options).toEqual(['Ana Abogada']);

    component.form.name = ' Acme SAS ';
    component.form.kind = 'ORGANIZATION';
    component.form.contactAddresses = ['gc@acme.co', ' '];
    component.save();

    const request = http.expectOne('/api/clients');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      name: 'Acme SAS',
      kind: 'ORGANIZATION',
      idNumber: null,
      phone: null,
      notes: null,
      responsibleLawyerId: 'lawyer-ana',
      contactAddresses: ['gc@acme.co'],
    });
    request.flush(client());
    http.expectOne('/api/clients').flush([client()]);
    http.expectOne('/api/clients/client-1/activities').flush([]);
    http.expectOne('/api/clients/client-1/history').flush([]);
    http.expectOne('/api/clients/client-1/correspondence').flush([]);

    expect(component.isFormOpen()).toBe(false);
    expect(component.selected()?.id).toBe('client-1');
  });

  it('names the client that already holds a contact address', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush([]);

    component.openCreate();
    component.form.name = 'Otro';
    component.form.contactAddresses = ['gc@acme.co'];
    component.save();
    http.expectOne('/api/clients').flush(
      { error: 'contact_address_taken', address: 'gc@acme.co', clientId: 'client-1', clientName: 'Acme SAS' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();

    expect(component.isFormOpen()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('gc@acme.co ya está registrado para el cliente Acme SAS.');
  });

  it('marks contact address changes in the history', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush([client()]);

    component.open(client());
    http.expectOne('/api/clients/client-1/activities').flush([]);
    http.expectOne('/api/clients/client-1/history').flush([
      { id: 'h1', clientId: 'client-1', activityId: null, field: 'contactAddress', oldValue: null,
        newValue: 'gc@acme.co', actorUserId: 'user_admin', createdAt: '2026-10-01T15:00:00Z' },
      { id: 'h2', clientId: 'client-1', activityId: null, field: 'responsibleLawyerId', oldValue: null,
        newValue: 'lawyer-ana', actorUserId: 'user_admin', createdAt: '2026-10-01T15:00:00Z' },
    ]);
    http.expectOne('/api/clients/client-1/correspondence').flush([]);
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('.history-row');
    expect(rows.length).toBe(2);
    expect(rows[0].classList).toContain('address-change');
    expect(rows[1].classList).not.toContain('address-change');
    expect(rows[1].textContent).toContain('Ana Abogada');
  });

  it('shows each Client email with what LegalGate did and the exact answer', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush([client()]);

    component.open(client());
    http.expectOne('/api/clients/client-1/activities').flush([]);
    http.expectOne('/api/clients/client-1/history').flush([]);
    http.expectOne('/api/clients/client-1/correspondence').flush([
      { id: 'c1', clientId: 'client-1', contactAddress: 'gc@acme.co', messageId: '<m1>', subject: 'Estado',
        body: 'Como va?', receivedAt: '2026-10-02T15:00:00Z', intent: 'status_inquiry',
        outcome: 'WOULD_HAVE_ANSWERED', answerText: 'Estimado(a):\n\nSu contrato...', purgedAt: null },
      { id: 'c2', clientId: 'client-1', contactAddress: 'ceo@acme.co', messageId: '<m2>', subject: 'Viejo',
        body: null, receivedAt: '2025-01-02T15:00:00Z', intent: 'needs_lawyer',
        outcome: 'FORWARDED', answerText: null, purgedAt: '2026-01-03T00:00:00Z' },
    ]);
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Reenviado (respuesta automática apagada)');
    expect(text).toContain('Respuesta que se habría enviado');
    expect(text).toContain('Su contrato...');
    expect(text).toContain('Reenviado al abogado');
    expect(text).toContain('El texto del cliente se borró al cumplir un año.');
  });

  it('opens an activity with only offered types and the type term, shows overdue work, and closes it after confirming', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush([client()]);
    component.open(client());
    http.expectOne('/api/clients/client-1/activities').flush([
      { id: 'a1', clientId: 'client-1', activityTypeId: 'type-draft', typeName: 'Redacción de contrato', kind: 'TURNAROUND',
        label: 'Contrato de arriendo', displayName: 'Contrato de arriendo', startDate: '2026-09-01', turnaroundDays: 5,
        dueDate: null, scheduledDate: null, termDays: null, assignedLawyerId: 'lawyer-ana', done: false, doneAt: null,
        expectedDate: '2026-09-08', overdue: true, termPassed: false },
    ]);
    http.expectOne('/api/clients/client-1/history').flush([]);
    http.expectOne('/api/clients/client-1/correspondence').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Contrato de arriendo');
    expect(fixture.nativeElement.textContent).toContain('Vencida');

    component.openActivityCreate();
    fixture.detectChanges();
    const offered = Array.from(fixture.nativeElement.querySelectorAll('#activityType option')).map((o: any) => o.textContent.trim());
    expect(offered).toEqual(['Redacción de contrato', 'Fallo de tutela']);
    component.changeActivityType('type-ruling');
    expect(component.activityForm()!.termDays).toBe(10);
    expect(component.activityForm()!.assignedLawyerId).toBe('lawyer-ana');
    component.saveActivity();
    const create = http.expectOne('/api/clients/client-1/activities');
    expect(create.request.method).toBe('POST');
    expect(create.request.body.activityTypeId).toBe('type-ruling');
    expect(create.request.body.termDays).toBe(10);
    create.flush({});
    http.expectOne('/api/clients/client-1/activities').flush([]);
    http.expectOne('/api/clients/client-1/history').flush([]);
    http.expectOne('/api/clients').flush([client()]);

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    component.completeActivity({ id: 'a1', displayName: 'Contrato de arriendo' } as any);
    http.expectNone('/api/clients/client-1/activities/a1/done');
    confirmSpy.mockReturnValue(true);
    component.completeActivity({ id: 'a1', displayName: 'Contrato de arriendo' } as any);
    expect(http.expectOne('/api/clients/client-1/activities/a1/done').request.method).toBe('POST');
    confirmSpy.mockRestore();
  });
});
