import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Component, ElementRef, OnInit, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivityKind, ActivityType } from '../activity-types/activity-types';
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

export interface Activity {
  id: string;
  clientId: string;
  activityTypeId: string;
  typeName: string;
  kind: ActivityKind;
  label: string | null;
  displayName: string;
  startDate: string;
  turnaroundDays: number | null;
  dueDate: string | null;
  scheduledDate: string | null;
  termDays: number | null;
  assignedLawyerId: string;
  done: boolean;
  doneAt: string | null;
  expectedDate: string | null;
  overdue: boolean;
  termPassed: boolean;
}

export interface ActivityForm {
  id: string | null;
  activityTypeId: string;
  label: string;
  startDate: string;
  dueDate: string;
  scheduledDate: string;
  termDays: number | null;
  assignedLawyerId: string;
}

const ACTIVITY_ERRORS: Record<string, string> = {
  scheduled_date_required: 'Indica la fecha de la actividad.',
  activity_type_retired: 'Ese tipo de actividad está retirado.',
  activity_type_required: 'Escoge un tipo de actividad.',
  assigned_lawyer_required: 'Escoge un abogado activo.',
  invalid_term_days: 'El término legal debe estar entre 1 y 365 días hábiles.',
  activity_done: 'La actividad ya está terminada y no se puede cambiar.',
};

export interface Correspondence {
  id: string;
  clientId: string;
  contactAddress: string;
  messageId: string;
  subject: string | null;
  body: string | null;
  receivedAt: string;
  intent: string | null;
  outcome: string | null;
  answerText: string | null;
  purgedAt: string | null;
}

const OUTCOME_LABELS: Record<string, string> = {
  ANSWERED: 'Respondido automáticamente',
  WOULD_HAVE_ANSWERED: 'Reenviado (respuesta automática apagada)',
  FORWARDED: 'Reenviado al abogado',
  HELD_BACK: 'Retenido: actividad vencida',
  IGNORED: 'Correo automático, ignorado',
};

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
  activity: 'Actividad creada',
  label: 'Etiqueta',
  startDate: 'Inicio',
  dueDate: 'Fecha límite',
  scheduledDate: 'Fecha programada',
  termDays: 'Término legal (días hábiles)',
  assignedLawyerId: 'Abogado asignado',
  status: 'Estado',
  engagement: 'Vinculado desde consulta',
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
  /** A Client to open on arrival, e.g. from a Consultation's Engagement. */
  readonly openClientId = input<string | null>(null);
  /** The Activity a reminder email links to, highlighted once the Client opens. */
  readonly openActivityId = input<string | null>(null);

  readonly clients = signal<Client[]>([]);
  readonly query = signal('');
  readonly idleOnly = signal(false);
  readonly selected = signal<Client | null>(null);
  readonly history = signal<HistoryEntry[]>([]);
  readonly correspondence = signal<Correspondence[]>([]);
  readonly activities = signal<Activity[]>([]);
  readonly activityTypes = signal<ActivityType[]>([]);
  readonly offeredTypes = computed(() => this.activityTypes().filter((type) => !type.retiredAt));
  readonly activityError = signal('');
  readonly activityForm = signal<ActivityForm | null>(null);
  private readonly activityDialog = viewChild<ElementRef<HTMLElement>>('activityDialog');
  // Focus moves into the Activity dialog when it opens.
  private readonly focusActivityDialog = effect(() => this.activityDialog()?.nativeElement.focus());
  readonly isFormOpen = signal(false);
  readonly editingId = signal<string | null>(null);
  readonly isSubmitting = signal(false);
  readonly errorMessage = signal('');
  readonly formError = signal('');
  form: ClientForm = blankClientForm();

  readonly activeLawyers = computed(() => this.lawyers().filter((lawyer) => lawyer.active && lawyer.id));

  ngOnInit(): void {
    this.load();
    this.http.get<ActivityType[]>(this.apiConfig.url('/api/activity-types')).subscribe({
      next: (types) => this.activityTypes.set(types),
      error: () => this.activityTypes.set([]),
    });
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
        const wanted = this.openClientId();
        const client = wanted && !this.selected() ? clients.find((candidate) => candidate.id === wanted) : null;
        if (client) {
          this.open(client);
        }
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
    this.activityForm.set(null);
    this.loadActivities(client.id);
    this.loadHistory(client.id);
    this.loadCorrespondence(client.id);
  }

  loadActivities(clientId: string): void {
    this.http.get<Activity[]>(this.apiConfig.url(`/api/clients/${clientId}/activities`)).subscribe({
      next: (activities) => {
        this.activities.set(activities);
        const wanted = this.openActivityId();
        if (wanted && activities.some((activity) => activity.id === wanted)) {
          setTimeout(() => document.getElementById(`activity-${wanted}`)?.scrollIntoView({ block: 'center' }));
        }
      },
      error: () => this.activities.set([]),
    });
  }

  typeFor(activityTypeId: string): ActivityType | undefined {
    return this.activityTypes().find((type) => type.id === activityTypeId);
  }

  formKind(): string | undefined {
    const form = this.activityForm();
    return form ? this.typeFor(form.activityTypeId)?.kind : undefined;
  }

  openActivityCreate(): void {
    const first = this.offeredTypes()[0];
    this.activityError.set('');
    this.activityForm.set({
      id: null,
      activityTypeId: first?.id ?? '',
      label: '',
      startDate: new Date().toLocaleDateString('en-CA', { timeZone: 'America/Bogota' }),
      dueDate: '',
      scheduledDate: '',
      termDays: first?.defaultTermDays ?? null,
      assignedLawyerId: this.selected()?.responsibleLawyerId ?? '',
    });
  }

  /** A new type brings its own statutory term, which the form shows for the firm to keep or clear. */
  changeActivityType(activityTypeId: string): void {
    this.activityForm.update((form) =>
      form && { ...form, activityTypeId, termDays: this.typeFor(activityTypeId)?.defaultTermDays ?? null },
    );
  }

  openActivityEdit(activity: Activity): void {
    this.activityError.set('');
    this.activityForm.set({
      id: activity.id,
      activityTypeId: activity.activityTypeId,
      label: activity.label ?? '',
      startDate: activity.startDate,
      dueDate: activity.dueDate ?? '',
      scheduledDate: activity.scheduledDate ?? '',
      termDays: activity.termDays,
      assignedLawyerId: activity.assignedLawyerId,
    });
  }

  cancelActivity(): void {
    this.activityError.set('');
    this.activityForm.set(null);
  }

  saveActivity(): void {
    const client = this.selected();
    const form = this.activityForm();
    if (!client || !form) {
      return;
    }
    const payload = {
      activityTypeId: form.activityTypeId,
      label: form.label.trim() || null,
      startDate: form.startDate || null,
      dueDate: form.dueDate || null,
      scheduledDate: form.scheduledDate || null,
      termDays: form.termDays || null,
      assignedLawyerId: form.assignedLawyerId || null,
    };
    const base = `/api/clients/${client.id}/activities`;
    const request = form.id
      ? this.http.put<Activity>(this.apiConfig.url(`${base}/${form.id}`), payload)
      : this.http.post<Activity>(this.apiConfig.url(base), payload);
    request.subscribe({
      next: () => {
        this.activityError.set('');
        this.activityForm.set(null);
        this.loadActivities(client.id);
        this.loadHistory(client.id);
        this.load();
      },
      error: (error: HttpErrorResponse) =>
        this.activityError.set(ACTIVITY_ERRORS[error.error?.error] ?? 'No se pudo guardar la actividad.'),
    });
  }

  completeActivity(activity: Activity): void {
    const client = this.selected();
    if (!client || !confirm(`¿Marcar "${activity.displayName}" como terminada? No se puede deshacer.`)) {
      return;
    }
    this.http
      .post<Activity>(this.apiConfig.url(`/api/clients/${client.id}/activities/${activity.id}/done`), {})
      .subscribe({
        next: () => {
          this.activityError.set('');
          this.loadActivities(client.id);
          this.loadHistory(client.id);
          this.load();
        },
        error: (error: HttpErrorResponse) =>
          this.activityError.set(ACTIVITY_ERRORS[error.error?.error] ?? 'No se pudo cerrar la actividad.'),
      });
  }

  activityWhen(activity: Activity): string {
    switch (activity.kind) {
      case 'TURNAROUND':
        return `Inició ${this.formatDate(activity.startDate)} · se espera ${this.formatDate(activity.expectedDate)}`;
      case 'DATED':
        return `Programada para ${this.formatDate(activity.scheduledDate)}`;
      default:
        return activity.expectedDate
          ? `Externa · término legal hasta ${this.formatDate(activity.expectedDate)}`
          : 'Externa · sin término fijo';
    }
  }

  formatDate(value: string | null): string {
    if (!value) {
      return '—';
    }
    const [year, month, day] = value.split('-').map(Number);
    return new Intl.DateTimeFormat('es-CO', { dateStyle: 'medium', timeZone: 'UTC' }).format(
      new Date(Date.UTC(year, month - 1, day)),
    );
  }

  close(): void {
    this.selected.set(null);
    this.history.set([]);
    this.correspondence.set([]);
    this.activities.set([]);
    this.activityForm.set(null);
  }

  loadCorrespondence(clientId: string): void {
    this.http.get<Correspondence[]>(this.apiConfig.url(`/api/clients/${clientId}/correspondence`)).subscribe({
      next: (entries) => this.correspondence.set(entries),
      error: () => this.correspondence.set([]),
    });
  }

  intentLabel(intent: string | null): string {
    return intent === 'status_inquiry' ? 'Pregunta por el estado' : intent === 'needs_lawyer' ? 'Requiere abogado' : 'Sin clasificar';
  }

  outcomeLabel(outcome: string | null): string {
    return outcome ? (OUTCOME_LABELS[outcome] ?? outcome) : 'En proceso';
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

  /** The confirmation names the consequence: the person is a stranger to LegalGate again. */
  deleteClient(client: Client): void {
    const message =
      `¿Eliminar a ${client.name}? Se borrarán sus correos de contacto, actividades, historial y correspondencia. ` +
      'Si vuelve a escribir, su correo pasará por diagnóstico como el de cualquier persona nueva.';
    if (!confirm(message)) {
      return;
    }
    this.http.delete(this.apiConfig.url(`/api/clients/${client.id}`)).subscribe({
      next: () => {
        this.close();
        this.load();
      },
      error: () => this.errorMessage.set('No se pudo eliminar el cliente.'),
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
