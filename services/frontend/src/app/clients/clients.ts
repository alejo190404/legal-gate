import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiConfigService } from '../config/api-config.service';
import { PixelIconComponent } from '../console/pixel-icon';

export interface Lawyer {
  id: string | null;
  displayName: string;
  email: string;
  active: boolean;
}

export interface Client {
  id: string;
  name: string;
  kind: 'PERSON' | 'ORGANIZATION';
  idNumber: string | null;
  phone: string | null;
  notes: string | null;
  responsibleLawyerId: string;
  contactAddresses: string[];
  openActivities: number;
  createdAt: string;
  updatedAt: string;
}

export interface HistoryEntry {
  id: string;
  clientId: string;
  activityId: string | null;
  field: string;
  oldValue: string | null;
  newValue: string | null;
  actorUserId: string | null;
  createdAt: string;
}

export interface ClientForm {
  name: string;
  kind: 'PERSON' | 'ORGANIZATION';
  idNumber: string;
  phone: string;
  notes: string;
  responsibleLawyerId: string;
  contactAddresses: string[];
}

export function blankClientForm(responsibleLawyerId = ''): ClientForm {
  return {
    name: '',
    kind: 'PERSON',
    idNumber: '',
    phone: '',
    notes: '',
    responsibleLawyerId,
    contactAddresses: [''],
  };
}

export function clientPayload(form: ClientForm) {
  return {
    name: form.name.trim(),
    kind: form.kind,
    idNumber: form.idNumber.trim() || null,
    phone: form.phone.trim() || null,
    notes: form.notes.trim() || null,
    responsibleLawyerId: form.responsibleLawyerId,
    contactAddresses: form.contactAddresses.map((address) => address.trim()).filter(Boolean),
  };
}

/** The server's reason, phrased for the form. A taken address names the Client holding it. */
export function clientErrorMessage(error: HttpErrorResponse): string {
  const body = error.error ?? {};
  switch (body.error) {
    case 'contact_address_taken':
      return `${body.address} ya está registrado para el cliente ${body.clientName}.`;
    case 'contact_address_required':
      return 'Agrega al menos un correo de contacto.';
    case 'invalid_contact_address':
      return 'Uno de los correos de contacto no es válido.';
    case 'client_name_required':
      return 'El nombre es obligatorio.';
    case 'responsible_lawyer_required':
      return 'Escoge un abogado responsable activo.';
    default:
      return 'No se pudo guardar el cliente.';
  }
}

const FIELD_LABELS: Record<string, string> = {
  client: 'Cliente creado',
  name: 'Nombre',
  kind: 'Tipo',
  idNumber: 'Cédula / NIT',
  phone: 'Teléfono',
  notes: 'Notas',
  responsibleLawyerId: 'Abogado responsable',
  contactAddress: 'Correo de contacto',
};

@Component({
  selector: 'app-clients',
  imports: [FormsModule, PixelIconComponent],
  templateUrl: './clients.html',
  styleUrls: ['../console/console.scss', './clients.scss'],
})
export class ClientsComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfigService);

  readonly lawyers = input<Lawyer[]>([]);

  readonly clients = signal<Client[]>([]);
  readonly query = signal('');
  readonly idleOnly = signal(false);
  readonly selected = signal<Client | null>(null);
  readonly history = signal<HistoryEntry[]>([]);
  readonly isFormOpen = signal(false);
  readonly editingId = signal<string | null>(null);
  readonly isSubmitting = signal(false);
  readonly errorMessage = signal('');
  readonly formError = signal('');
  form: ClientForm = blankClientForm();

  readonly activeLawyers = computed(() => this.lawyers().filter((lawyer) => lawyer.active && lawyer.id));

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    let params = new HttpParams();
    if (this.query().trim()) {
      params = params.set('q', this.query().trim());
    }
    if (this.idleOnly()) {
      params = params.set('idle', 'true');
    }
    this.http.get<Client[]>(this.apiConfig.url('/api/clients'), { params }).subscribe({
      next: (clients) => {
        this.clients.set(clients);
        this.errorMessage.set('');
      },
      error: () => this.errorMessage.set('No se pudieron cargar los clientes.'),
    });
  }

  search(value: string): void {
    this.query.set(value);
    this.load();
  }

  toggleIdle(): void {
    this.idleOnly.update((idle) => !idle);
    this.load();
  }

  open(client: Client): void {
    this.selected.set(client);
    this.loadHistory(client.id);
  }

  close(): void {
    this.selected.set(null);
    this.history.set([]);
  }

  loadHistory(clientId: string): void {
    this.http.get<HistoryEntry[]>(this.apiConfig.url(`/api/clients/${clientId}/history`)).subscribe({
      next: (history) => this.history.set(history),
      error: () => this.history.set([]),
    });
  }

  openCreate(): void {
    this.form = blankClientForm(this.activeLawyers()[0]?.id ?? '');
    this.editingId.set(null);
    this.formError.set('');
    this.isFormOpen.set(true);
  }

  openEdit(client: Client): void {
    this.form = {
      name: client.name,
      kind: client.kind,
      idNumber: client.idNumber ?? '',
      phone: client.phone ?? '',
      notes: client.notes ?? '',
      responsibleLawyerId: client.responsibleLawyerId,
      contactAddresses: [...client.contactAddresses],
    };
    this.editingId.set(client.id);
    this.formError.set('');
    this.isFormOpen.set(true);
  }

  closeForm(): void {
    this.isFormOpen.set(false);
  }

  addAddress(): void {
    this.form.contactAddresses.push('');
  }

  removeAddress(index: number): void {
    this.form.contactAddresses.splice(index, 1);
  }

  save(): void {
    const id = this.editingId();
    const url = this.apiConfig.url(id ? `/api/clients/${id}` : '/api/clients');
    const payload = clientPayload(this.form);
    const request = id ? this.http.put<Client>(url, payload) : this.http.post<Client>(url, payload);
    this.isSubmitting.set(true);
    request.subscribe({
      next: (client) => {
        this.isSubmitting.set(false);
        this.isFormOpen.set(false);
        this.load();
        this.open(client);
      },
      error: (error: HttpErrorResponse) => {
        this.isSubmitting.set(false);
        this.formError.set(clientErrorMessage(error));
      },
    });
  }

  lawyerName(lawyerId: string | null): string {
    return this.lawyers().find((lawyer) => lawyer.id === lawyerId)?.displayName ?? 'Sin asignar';
  }

  kindLabel(kind: string): string {
    return kind === 'ORGANIZATION' ? 'Organización' : 'Persona';
  }

  fieldLabel(field: string): string {
    return FIELD_LABELS[field] ?? field;
  }

  historyValue(entry: HistoryEntry, value: string | null): string {
    if (value === null) {
      return '—';
    }
    if (entry.field === 'responsibleLawyerId' || entry.field === 'assignedLawyerId') {
      return this.lawyerName(value);
    }
    if (entry.field === 'kind') {
      return this.kindLabel(value);
    }
    return value;
  }

  isAddressChange(entry: HistoryEntry): boolean {
    return entry.field === 'contactAddress';
  }

  formatDateTime(value: string): string {
    return new Intl.DateTimeFormat('es-CO', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
  }
}
