import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

type ViewName = 'landing' | 'login' | 'console';

interface ClassificationResult {
  label: string;
  matchedUrgentKeywords: string[];
  explanation: string;
}

interface NotificationStatus {
  emailQueued: boolean;
  calendarUpdateQueued: boolean;
  destinationEmail: string | null;
  preferredWindow: string | null;
}

interface Consultation {
  id: string;
  tenantId: string;
  clientName: string;
  clientEmail: string;
  summary: string;
  preferredWindow: string | null;
  status: string;
  urgency: 'NORMAL' | 'URGENT' | string;
  classification: ClassificationResult;
  notifications: NotificationStatus;
  createdAt: string;
}

interface ConsultationListResponse {
  tenantId: string;
  consultations: Consultation[];
}

interface CreateConsultationForm {
  clientName: string;
  clientEmail: string;
  summary: string;
  preferredWindow: string;
}

interface LoginForm {
  email: string;
  password: string;
}

@Component({
  selector: 'app-root',
  imports: [FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly http = inject(HttpClient);

  // TODO(auth): Replace this hardcoded demo gate with the real LegalGate auth provider.
  // Keep credentials out of templates/landing/login copy; only authenticated state should
  // unlock the console. This prevents demo-only details from leaking into production UI.
  private readonly demoEmail = 'admin@firma-demo.test';
  private readonly demoPassword = 'LegalGateDemo2026!';

  // TODO(routing): Move landing/login/console to Angular routes once auth is real.
  // For this prototype, a single component keeps the test scope small and protects the
  // existing public landing experience while validating the backend-connected console.
  readonly view = signal<ViewName>('landing');
  readonly tenantId = signal('firma-demo');
  readonly sessionEmail = signal('');
  readonly consultations = signal<Consultation[]>([]);
  readonly isLoading = signal(false);
  readonly isSubmitting = signal(false);
  readonly statusMessage = signal('Listo para iniciar sesión con credenciales demo.');
  readonly errorMessage = signal('');

  readonly loginForm: LoginForm = {
    email: '',
    password: '',
  };

  readonly form: CreateConsultationForm = {
    clientName: '',
    clientEmail: '',
    summary: '',
    preferredWindow: '',
  };

  readonly totalConsultations = computed(() => this.consultations().length);
  readonly urgentConsultations = computed(
    () => this.consultations().filter((consultation) => consultation.urgency === 'URGENT').length,
  );
  readonly queuedNotifications = computed(
    () => this.consultations().filter((consultation) => consultation.notifications.emailQueued).length,
  );
  readonly latestConsultation = computed(() => this.consultations()[0] ?? null);

  showLanding(): void {
    this.view.set('landing');
    this.errorMessage.set('');
  }

  showLogin(): void {
    this.view.set('login');
    this.errorMessage.set('');
    this.statusMessage.set('Ingresa con tu cuenta de administrador para abrir el panel.');
  }

  login(): void {
    const email = this.loginForm.email.trim().toLowerCase();
    const password = this.loginForm.password;

    if (email !== this.demoEmail || password !== this.demoPassword) {
      this.errorMessage.set('Credenciales inválidas. Verifica tu email y contraseña.');
      return;
    }

    this.sessionEmail.set(this.demoEmail);
    this.view.set('console');
    this.statusMessage.set('Sesión demo iniciada. Cargando datos de la firma…');
    this.loadConsultations();
  }

  logout(): void {
    this.sessionEmail.set('');
    this.consultations.set([]);
    this.showLanding();
  }

  loadConsultations(): void {
    this.isLoading.set(true);
    this.errorMessage.set('');
    this.http
      .get<ConsultationListResponse>(`/api/admin/tenants/${this.tenantId()}/consultations`)
      .subscribe({
        next: (response) => {
          this.consultations.set([...response.consultations].reverse());
          this.statusMessage.set('Datos de prueba cargados desde el servicio de intake.');
          this.isLoading.set(false);
        },
        error: () => {
          this.errorMessage.set('No se pudo conectar con el API local de LegalGate.');
          this.statusMessage.set('Revisa que el backend esté activo en el puerto 8081.');
          this.isLoading.set(false);
        },
      });
  }

  createConsultation(): void {
    if (!this.form.clientName.trim() || !this.form.clientEmail.trim() || !this.form.summary.trim()) {
      this.errorMessage.set('Completa nombre, email y resumen para crear la consulta.');
      return;
    }

    this.isSubmitting.set(true);
    this.errorMessage.set('');
    this.http
      .post<Consultation>(`/api/tenants/${this.tenantId()}/consultations`, {
        clientName: this.form.clientName.trim(),
        clientEmail: this.form.clientEmail.trim(),
        summary: this.form.summary.trim(),
        preferredWindow: this.form.preferredWindow.trim(),
      })
      .subscribe({
        next: (consultation) => {
          this.consultations.update((items) => [consultation, ...items]);
          this.statusMessage.set('Consulta creada y enrutada para revisión del equipo legal.');
          this.form.clientName = '';
          this.form.clientEmail = '';
          this.form.summary = '';
          this.form.preferredWindow = '';
          this.isSubmitting.set(false);
        },
        error: () => {
          this.errorMessage.set('No se pudo crear la consulta. Intenta nuevamente.');
          this.isSubmitting.set(false);
        },
      });
  }

  urgencyLabel(consultation: Consultation): string {
    return consultation.urgency === 'URGENT' ? 'Urgente' : 'Normal';
  }

  formatDate(value: string): string {
    return new Intl.DateTimeFormat('es-CO', {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(value));
  }
}
