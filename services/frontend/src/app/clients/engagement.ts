import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ApiConfigService } from '../config/api-config.service';
import { Page } from '../page';
import { Client, ClientForm, Lawyer, blankClientForm, clientErrorMessage, clientPayload } from './clients';

interface Engagement {
  consultationId: string;
  clientId: string | null;
  draft: {
    name: string;
    kind: 'PERSON' | 'ORGANIZATION';
    responsibleLawyerId: string | null;
    contactAddresses: string[];
  } | null;
}

/** The Engagement action on a Consultation: link to its Client, or take its potential client on. */
@Component({
  selector: 'app-engagement',
  imports: [FormsModule],
  templateUrl: './engagement.html',
  styleUrls: ['../console/console.scss', './clients.scss'],
})
export class EngagementComponent {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfigService);

  readonly consultationId = input.required<string>();
  readonly lawyers = input<Lawyer[]>([]);
  readonly openClient = output<string>();

  readonly engagement = signal<Engagement | null>(null);
  readonly clients = signal<Client[]>([]);
  private finding?: Subscription;
  readonly isOpen = signal(false);
  readonly mode = signal<'new' | 'existing'>('new');
  readonly existingClientId = signal('');
  readonly isSubmitting = signal(false);
  readonly formError = signal('');
  readonly activeLawyers = computed(() => this.lawyers().filter((lawyer) => lawyer.active && lawyer.id));
  form: ClientForm = blankClientForm();

  constructor() {
    effect(() => this.load(this.consultationId()));
  }

  load(consultationId: string): void {
    this.engagement.set(null);
    this.isOpen.set(false);
    this.http
      .get<Engagement>(this.apiConfig.url(`/api/consultations/${consultationId}/engagement`))
      .subscribe({ next: (engagement) => this.engagement.set(engagement), error: () => this.engagement.set(null) });
  }

  start(): void {
    const draft = this.engagement()?.draft;
    this.form = {
      ...blankClientForm(draft?.responsibleLawyerId ?? this.activeLawyers()[0]?.id ?? ''),
      name: draft?.name ?? '',
      kind: draft?.kind ?? 'PERSON',
      contactAddresses: draft?.contactAddresses.length ? [...draft.contactAddresses] : [''],
    };
    this.mode.set('new');
    this.formError.set('');
    this.isOpen.set(true);
    this.findClients('');
  }

  /** The first page of Clients matching `query`; a newer search cancels an older one still in flight. */
  findClients(query: string): void {
    this.finding?.unsubscribe();
    this.existingClientId.set('');
    const q = query.trim();
    this.finding = this.http
      .get<Page<Client>>(this.apiConfig.url('/api/clients'), { params: q ? { q } : {} })
      .subscribe({
        next: (page) => this.clients.set(page.items),
        error: () => this.clients.set([]),
      });
  }

  close(): void {
    this.isOpen.set(false);
  }

  submit(): void {
    const body =
      this.mode() === 'existing'
        ? { clientId: this.existingClientId(), client: null }
        : { clientId: null, client: clientPayload(this.form) };
    this.isSubmitting.set(true);
    this.http
      .post<Client>(this.apiConfig.url(`/api/consultations/${this.consultationId()}/engage`), body)
      .subscribe({
        next: (client) => {
          this.isSubmitting.set(false);
          this.isOpen.set(false);
          this.engagement.set({ consultationId: this.consultationId(), clientId: client.id, draft: null });
        },
        error: (error: HttpErrorResponse) => {
          this.isSubmitting.set(false);
          this.formError.set(
            error.error?.error === 'consultation_already_engaged'
              ? 'Esta consulta ya estaba vinculada a un cliente.'
              : clientErrorMessage(error),
          );
        },
      });
  }
}
