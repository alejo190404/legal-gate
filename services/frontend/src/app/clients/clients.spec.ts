import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiConfigService } from '../config/api-config.service';
import { Client, ClientsComponent } from './clients';

const LAWYERS = [
  { id: 'lawyer-ana', displayName: 'Ana Abogada', email: 'ana@firm.co', active: true },
  { id: 'lawyer-rita', displayName: 'Rita Retirada', email: 'rita@firm.co', active: false },
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
    http.expectOne('/api/clients/client-1/history').flush([]);

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
    http.expectOne('/api/clients/client-1/history').flush([
      { id: 'h1', clientId: 'client-1', activityId: null, field: 'contactAddress', oldValue: null,
        newValue: 'gc@acme.co', actorUserId: 'user_admin', createdAt: '2026-10-01T15:00:00Z' },
      { id: 'h2', clientId: 'client-1', activityId: null, field: 'responsibleLawyerId', oldValue: null,
        newValue: 'lawyer-ana', actorUserId: 'user_admin', createdAt: '2026-10-01T15:00:00Z' },
    ]);
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('.history-row');
    expect(rows.length).toBe(2);
    expect(rows[0].classList).toContain('address-change');
    expect(rows[1].classList).not.toContain('address-change');
    expect(rows[1].textContent).toContain('Ana Abogada');
  });
});
