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

/** A forwarded Client email received on 2026-10-0{n}. */
function email(n: number) {
  return {
    id: `c${n}`, clientId: 'client-1', contactAddress: 'gc@acme.co', messageId: `<m${n}>`, subject: `Correo ${n}`,
    body: 'Hola', receivedAt: `2026-10-0${n}T15:00:00Z`, intent: 'needs_lawyer', outcome: 'FORWARDED',
    answerText: null, purgedAt: null,
  };
}

function loadMoreButton(fixture: { nativeElement: HTMLElement }): HTMLButtonElement | undefined {
  return Array.from(fixture.nativeElement.querySelectorAll('button')).find(
    (button) => button.textContent?.trim() === 'Cargar más',
  ) as HTMLButtonElement | undefined;
}

describe('ClientsComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ClientsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), ApiConfigService],
    }).compileComponents();
  });

  afterEach(() => {
    vi.useRealTimers();
    TestBed.inject(HttpTestingController).verify();
  });

  function setup() {
    const fixture = TestBed.createComponent(ClientsComponent);
    fixture.componentRef.setInput('lawyers', LAWYERS);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/activity-types').flush(TYPES);
    return { fixture, http, component: fixture.componentInstance };
  }

  it('searches once typing pauses and drops the answer to an older search', () => {
    vi.useFakeTimers();
    const { http, component } = setup();
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });

    component.search('a');
    component.search('ac');
    vi.advanceTimersByTime(299);
    http.expectNone((request) => request.url === '/api/clients');
    vi.advanceTimersByTime(1);
    const older = http.expectOne('/api/clients?q=ac');

    component.search('acme');
    vi.advanceTimersByTime(300);
    expect(older.cancelled).toBe(true);
    http.expectOne('/api/clients?q=acme').flush({ items: [client({ name: 'Acme Legal' })], nextCursor: null });
    expect(component.clients().map((c) => c.name)).toEqual(['Acme Legal']);
  });

  it('appends the next page on Cargar más and starts again from the first page on a new search', () => {
    vi.useFakeTimers();
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: 'after-acme' });
    fixture.detectChanges();

    loadMoreButton(fixture)!.click();
    http.expectOne('/api/clients?cursor=after-acme').flush({ items: [client({ id: 'client-2', name: 'Beta' })], nextCursor: null });
    fixture.detectChanges();
    expect(component.clients().map((c) => c.name)).toEqual(['Acme SAS', 'Beta']);
    expect(loadMoreButton(fixture)).toBeUndefined();

    component.toggleIdle();
    http.expectOne('/api/clients?idle=true').flush({ items: [client()], nextCursor: 'after-acme' });
    fixture.detectChanges();
    loadMoreButton(fixture)!.click();
    const more = http.expectOne('/api/clients?idle=true&cursor=after-acme');
    component.search('beta');
    vi.advanceTimersByTime(300);
    expect(more.cancelled).toBe(true);
    http.expectOne('/api/clients?q=beta&idle=true').flush({ items: [client({ id: 'client-2', name: 'Beta' })], nextCursor: null });
    expect(component.clients().map((c) => c.name)).toEqual(['Beta']);
  });

  it('runs a search still waiting on the debounce when Cargar más is clicked, instead of paging the old one', () => {
    vi.useFakeTimers();
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: 'after-acme' });
    fixture.detectChanges();

    component.search('beta');
    loadMoreButton(fixture)!.click();
    http.expectOne('/api/clients?q=beta').flush({ items: [client({ id: 'client-2', name: 'Beta' })], nextCursor: null });
    vi.advanceTimersByTime(300);
    http.expectNone((request) => request.url === '/api/clients');
    expect(component.clients().map((c) => c.name)).toEqual(['Beta']);
  });

  it('opens a linked Client and highlights its Activity without looking for it in the list', () => {
    const fixture = TestBed.createComponent(ClientsComponent);
    fixture.componentRef.setInput('openClientId', 'client-77');
    fixture.componentRef.setInput('openActivityId', 'activity-1');
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/activity-types').flush(TYPES);
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: 'after-acme' });
    http.expectOne('/api/clients/client-77').flush(client({ id: 'client-77', name: 'Zeta Ltda' }));
    http.expectOne('/api/clients/client-77/activities').flush([{
      id: 'activity-1', clientId: 'client-77', activityTypeId: 'type-draft', typeName: 'Redacción de contrato',
      kind: 'TURNAROUND', label: null, displayName: 'Redacción de contrato', startDate: '2026-10-01', turnaroundDays: 5,
      dueDate: null, scheduledDate: null, termDays: null, assignedLawyerId: 'lawyer-ana', done: false, doneAt: null,
      expectedDate: '2026-10-08', overdue: false, termPassed: false,
    }]);
    http.expectOne('/api/clients/client-77/history').flush([]);
    http.expectOne('/api/clients/client-77/correspondence').flush([]);
    fixture.detectChanges();

    expect(fixture.componentInstance.selected()?.name).toBe('Zeta Ltda');
    expect(fixture.nativeElement.querySelector('.linked')?.textContent).toContain('Redacción de contrato');
  });

  it('reports a linked Client that no longer exists as missing', () => {
    const fixture = TestBed.createComponent(ClientsComponent);
    fixture.componentRef.setInput('openClientId', 'client-gone');
    const http = TestBed.inject(HttpTestingController);
    const missing = vi.fn();
    fixture.componentInstance.openClientMissing.subscribe(missing);
    fixture.detectChanges();
    http.expectOne('/api/activity-types').flush(TYPES);
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });
    http.expectOne('/api/clients/client-gone').flush({ error: 'client_not_found' }, { status: 404, statusText: 'Not Found' });

    expect(missing).toHaveBeenCalledTimes(1);
    expect(fixture.componentInstance.selected()).toBeNull();
  });

  it('lists clients and searches and filters them on the server', () => {
    vi.useFakeTimers();
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Acme SAS');
    expect(fixture.nativeElement.textContent).toContain('gc@acme.co');

    component.search('acme');
    vi.advanceTimersByTime(300);
    http.expectOne('/api/clients?q=acme').flush({ items: [client()], nextCursor: null });
    component.toggleIdle();
    http.expectOne('/api/clients?q=acme&idle=true').flush({ items: [], nextCursor: null });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No hay clientes en esta vista');
  });

  it('creates a client with only active lawyers offered as responsible', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush({ items: [], nextCursor: null });

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
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });
    http.expectOne('/api/clients/client-1/activities').flush([]);
    http.expectOne('/api/clients/client-1/history').flush([]);
    http.expectOne('/api/clients/client-1/correspondence').flush([]);

    expect(component.isFormOpen()).toBe(false);
    expect(component.selected()?.id).toBe('client-1');
  });

  it('names the client that already holds a contact address', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush({ items: [], nextCursor: null });

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
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });

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

  it('names who made each History change instead of their user id', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });
    component.open(client());
    http.expectOne('/api/clients/client-1/activities').flush([]);
    http.expectOne('/api/clients/client-1/history').flush([
      { id: 'h1', clientId: 'client-1', activityId: null, field: 'name', oldValue: 'Acme', newValue: 'Acme SAS',
        actorUserId: 'user_3K3YFS142', actorName: 'Ana Abogada', createdAt: '2026-10-01T15:00:00Z' },
      { id: 'h2', clientId: 'client-1', activityId: null, field: 'name', oldValue: null, newValue: 'Acme',
        actorUserId: null, actorName: null, createdAt: '2026-10-01T14:00:00Z' },
    ]);
    http.expectOne('/api/clients/client-1/correspondence').flush([]);
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('.history-row');
    expect(rows[0].textContent).toContain('Ana Abogada');
    expect(rows[0].textContent).not.toContain('user_3K3YFS142');
    expect(rows[1].textContent).toContain('sistema');
  });

  it('shows each Client email with what LegalGate did and the exact answer', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });

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
    expect(text).toContain('Pregunta por el estado');
    expect(text).toContain('Reenviado (respuesta automática apagada)');
    expect(text).toContain('Respuesta que se habría enviado');
    expect(text).toContain('Su contrato...');
    expect(text).toContain('Reenviado al abogado');
    expect(text).toContain('El texto del cliente se borró al cumplir un año.');
  });

  it('shows the 3 latest emails inline and the full correspondence in the Archivo dialog', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });
    component.open(client());
    http.expectOne('/api/clients/client-1/activities').flush([]);
    http.expectOne('/api/clients/client-1/history').flush([]);
    // The server sends newest first.
    http.expectOne('/api/clients/client-1/correspondence').flush([email(4), email(3), email(2), email(1)]);
    fixture.detectChanges();

    const panel = fixture.nativeElement.querySelector('.detail');
    expect(panel.textContent).toContain('Correo 4');
    expect(panel.textContent).toContain('Correo 2');
    expect(panel.textContent).not.toContain('Correo 1');
    expect(fixture.nativeElement.querySelector('[aria-labelledby="archive-title"]')).toBeNull();

    const archive = [...panel.querySelectorAll('button')].find((b: HTMLButtonElement) => b.textContent!.includes('Archivo')) as HTMLButtonElement;
    archive.click();
    fixture.detectChanges();

    const dialog = fixture.nativeElement.querySelector('[role="dialog"][aria-labelledby="archive-title"]');
    expect([1, 2, 3, 4].every((n) => dialog.textContent.includes(`Correo ${n}`))).toBe(true);

    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[aria-labelledby="archive-title"]')).toBeNull();
  });

  it('offers no Archivo when the Client has written 3 times or fewer', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });
    component.open(client());
    http.expectOne('/api/clients/client-1/activities').flush([]);
    http.expectOne('/api/clients/client-1/history').flush([]);
    http.expectOne('/api/clients/client-1/correspondence').flush([email(1)]);
    fixture.detectChanges();

    const buttons = [...fixture.nativeElement.querySelectorAll('.detail button')] as HTMLButtonElement[];
    expect(buttons.some((b) => b.textContent!.includes('Archivo'))).toBe(false);
  });

  it('opens an activity with only offered types and the type term, shows overdue work, and closes it after confirming', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });
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
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    component.completeActivity({ id: 'a1', displayName: 'Contrato de arriendo' } as any);
    http.expectNone('/api/clients/client-1/activities/a1/done');
    confirmSpy.mockReturnValue(true);
    component.completeActivity({ id: 'a1', displayName: 'Contrato de arriendo' } as any);
    expect(http.expectOne('/api/clients/client-1/activities/a1/done').request.method).toBe('POST');
    confirmSpy.mockRestore();
  });

  it('edits and creates Activities in a dialog that saving, the backdrop or Escape closes', async () => {
    const { fixture, http } = setup();
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });
    fixture.componentInstance.open(client());
    http.expectOne('/api/clients/client-1/activities').flush([
      { id: 'a1', clientId: 'client-1', activityTypeId: 'type-draft', typeName: 'Redacción de contrato', kind: 'TURNAROUND',
        label: 'Contrato de arriendo', displayName: 'Contrato de arriendo', startDate: '2026-09-01', turnaroundDays: 5,
        dueDate: null, scheduledDate: null, termDays: null, assignedLawyerId: 'lawyer-ana', done: false, doneAt: null,
        expectedDate: '2026-09-08', overdue: false, termPassed: false },
    ]);
    http.expectOne('/api/clients/client-1/history').flush([]);
    http.expectOne('/api/clients/client-1/correspondence').flush([]);
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;
    const button = (text: string) =>
      Array.from(page.querySelectorAll('button')).find((b) => b.textContent!.trim() === text)!;
    const dialog = () => page.querySelector<HTMLElement>('[role="dialog"]');

    button('Editar').click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(dialog()!.getAttribute('aria-labelledby')).toBe('activity-form-title');
    expect(dialog()!.textContent).toContain('Editar actividad');
    expect(dialog()!.contains(document.activeElement)).toBe(true);
    expect(page.querySelector<HTMLInputElement>('#activityLabel')!.value).toBe('Contrato de arriendo');
    expect(page.querySelector('#activityType')).toBeNull();
    expect(dialog()!.textContent).toContain('consultas de estado');

    dialog()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(dialog()).toBeNull();

    button('+ Nueva actividad').click();
    fixture.detectChanges();
    expect(dialog()!.textContent).toContain('Nueva actividad');
    expect(page.querySelector('#activityType')).not.toBeNull();
    page.querySelector<HTMLElement>('.modal-backdrop')!.click();
    fixture.detectChanges();
    expect(dialog()).toBeNull();

    button('+ Nueva actividad').click();
    fixture.detectChanges();
    button('Guardar actividad').click();
    http.expectOne('/api/clients/client-1/activities').flush({});
    http.expectOne('/api/clients/client-1/activities').flush([]);
    http.expectOne('/api/clients/client-1/history').flush([]);
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });
    fixture.detectChanges();
    expect(dialog()).toBeNull();
  });

  it('clears an Activity error once a later save or close succeeds', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });
    component.open(client());
    http.expectOne('/api/clients/client-1/activities').flush([]);
    http.expectOne('/api/clients/client-1/history').flush([]);
    http.expectOne('/api/clients/client-1/correspondence').flush([]);

    component.openActivityCreate();
    component.saveActivity();
    http.expectOne('/api/clients/client-1/activities').flush(
      { error: 'scheduled_date_required' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Indica la fecha de la actividad.');

    component.saveActivity();
    http.expectOne('/api/clients/client-1/activities').flush({});
    http.expectOne('/api/clients/client-1/activities').flush([]);
    http.expectOne('/api/clients/client-1/history').flush([]);
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Indica la fecha de la actividad.');

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    component.completeActivity({ id: 'a1', displayName: 'Audiencia' } as any);
    http.expectOne('/api/clients/client-1/activities/a1/done').flush(
      { error: 'unknown' }, { status: 500, statusText: 'Server Error' });
    expect(component.activityError()).toBe('No se pudo cerrar la actividad.');
    component.completeActivity({ id: 'a1', displayName: 'Audiencia' } as any);
    http.expectOne('/api/clients/client-1/activities/a1/done').flush({});
    http.expectOne('/api/clients/client-1/activities').flush([]);
    http.expectOne('/api/clients/client-1/history').flush([]);
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });
    expect(component.activityError()).toBe('');
    confirmSpy.mockRestore();
  });

  it('deletes a client only after a confirmation that names the diagnostics consequence', () => {
    const { http, component } = setup();
    http.expectOne('/api/clients').flush({ items: [client()], nextCursor: null });
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);

    component.deleteClient(client());
    http.expectNone('/api/clients/client-1');
    expect(confirmSpy.mock.calls[0][0]).toContain('pasará por diagnóstico');

    confirmSpy.mockReturnValue(true);
    component.deleteClient(client());
    const request = http.expectOne('/api/clients/client-1');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
    http.expectOne('/api/clients').flush({ items: [], nextCursor: null });
    confirmSpy.mockRestore();
  });
});
