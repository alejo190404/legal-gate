import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiConfigService } from '../config/api-config.service';
import { EngagementComponent } from './engagement';

const LAWYERS = [
  { id: 'lawyer-ana', displayName: 'Ana Abogada', email: 'ana@firm.co', active: true },
  { id: 'lawyer-luis', displayName: 'Luis Litigante', email: 'luis@firm.co', active: true },
];

describe('EngagementComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EngagementComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), ApiConfigService],
    }).compileComponents();
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  function setup() {
    const fixture = TestBed.createComponent(EngagementComponent);
    fixture.componentRef.setInput('consultationId', 'consultation-1');
    fixture.componentRef.setInput('lawyers', LAWYERS);
    fixture.detectChanges();
    return { fixture, http: TestBed.inject(HttpTestingController), component: fixture.componentInstance };
  }

  it('opens a form prefilled from the consultation and engages it into a new client', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/consultations/consultation-1/engagement').flush({
      consultationId: 'consultation-1',
      clientId: null,
      draft: { name: 'Maria', kind: 'PERSON', responsibleLawyerId: 'lawyer-luis', contactAddresses: ['maria@example.com'] },
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Vincular como cliente');

    component.start();
    http.expectOne('/api/clients').flush({ items: [], nextCursor: null });
    expect(component.form.name).toBe('Maria');
    expect(component.form.contactAddresses).toEqual(['maria@example.com']);
    expect(component.form.responsibleLawyerId).toBe('lawyer-luis');

    component.form.name = 'Maria Perez';
    component.submit();
    const engage = http.expectOne('/api/consultations/consultation-1/engage');
    expect(engage.request.body.clientId).toBeNull();
    expect(engage.request.body.client.name).toBe('Maria Perez');
    engage.flush({ id: 'client-9' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Ver cliente vinculado');
  });

  it('attaches to an existing client, searching for one past the first page', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/consultations/consultation-1/engagement').flush({
      consultationId: 'consultation-1', clientId: null,
      draft: { name: 'Legal', kind: 'PERSON', responsibleLawyerId: 'lawyer-ana', contactAddresses: ['legal@acme.co'] },
    });

    component.start();
    http.expectOne('/api/clients').flush({ items: [{ id: 'client-1', name: 'Acme SAS' }], nextCursor: 'after-acme' });
    component.mode.set('existing');
    fixture.detectChanges();
    expect(optionNames()).toEqual(['Acme SAS']);

    component.findClients(' zeta ');
    http.expectOne('/api/clients?q=zeta').flush({ items: [{ id: 'client-77', name: 'Zeta Ltda' }], nextCursor: null });
    fixture.detectChanges();
    expect(optionNames()).toEqual(['Zeta Ltda']);

    component.existingClientId.set('client-77');
    component.submit();
    expect(http.expectOne('/api/consultations/consultation-1/engage').request.body).toEqual({ clientId: 'client-77', client: null });

    function optionNames(): string[] {
      return Array.from(fixture.nativeElement.querySelectorAll('#engagementClient option:not([disabled])')).map(
        (option) => (option as HTMLElement).textContent!.trim(),
      );
    }
  });

  it('links an engaged consultation to its client', () => {
    const { fixture, http, component } = setup();
    http.expectOne('/api/consultations/consultation-1/engagement').flush({
      consultationId: 'consultation-1', clientId: 'client-1', draft: null,
    });
    fixture.detectChanges();
    let opened: string | null = null;
    component.openClient.subscribe((id) => (opened = id));

    fixture.nativeElement.querySelector('button').click();

    expect(opened).toBe('client-1');
    expect(fixture.nativeElement.textContent).not.toContain('Vincular como cliente');
  });
});
