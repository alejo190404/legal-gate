import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiConfigService } from './config/api-config.service';

type ViewName = 'landing' | 'login' | 'register' | 'console';
type ConsoleSection = 'dashboard' | 'consultas' | 'configuracion';

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

interface TenantSettingsResponse {
  tenantId: string;
  urgentKeywords: string[];
  consultationWindows: string[];
  destinationEmail: string | null;
  intakeEmail: string | null;
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

interface RegisterForm {
  email: string;
  password: string;
  firmName: string;
}

interface TenantSettingsForm {
  intakeEmail: string;
  destinationEmail: string;
  urgentKeywords: string;
  consultationWindows: string;
}

interface SessionResponse {
  email: string;
  tenantId: string;
  displayName: string;
  role: 'FIRM_ADMIN' | string;
}

@Component({
  selector: 'app-root',
  imports: [FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfigService);

  // TODO(routing): Move landing/login/console to Angular routes once auth is real.
  // For this prototype, a single component keeps the test scope small and protects the
  // existing public landing experience while validating the backend-connected console.
  readonly view = signal<ViewName>('landing');
  readonly tenantId = signal('');
  readonly sessionEmail = signal('');
  readonly consultations = signal<Consultation[]>([]);
  readonly tenantSettings = signal<TenantSettingsResponse | null>(null);
  readonly activeConsoleSection = signal<ConsoleSection>('dashboard');
  readonly isConsoleMenuOpen = signal(false);
  readonly isLoading = signal(false);
  readonly isSubmitting = signal(false);
  readonly statusMessage = signal('Listo para iniciar sesion.');
  readonly errorMessage = signal('');

  readonly loginForm: LoginForm = {
    email: '',
    password: '',
  };

  readonly registerForm: RegisterForm = {
    email: '',
    password: '',
    firmName: '',
  };

  readonly form: CreateConsultationForm = {
    clientName: '',
    clientEmail: '',
    summary: '',
    preferredWindow: '',
  };

  readonly settingsForm: TenantSettingsForm = {
    intakeEmail: '',
    destinationEmail: '',
    urgentKeywords: 'audiencia, captura, tutela, vencimiento',
    consultationWindows: '',
  };

  readonly totalConsultations = computed(() => this.consultations().length);
  readonly urgentConsultations = computed(
    () => this.consultations().filter((consultation) => consultation.urgency === 'URGENT').length,
  );
  readonly queuedNotifications = computed(
    () =>
      this.consultations().filter((consultation) => consultation.notifications.emailQueued).length,
  );
  readonly latestConsultation = computed(() => this.consultations()[0] ?? null);
  readonly configuredIntakeEmail = computed(
    () => this.tenantSettings()?.intakeEmail || 'Sin configurar',
  );
  readonly consoleSections: ReadonlyArray<{ id: ConsoleSection; label: string }> = [
    { id: 'dashboard', label: 'Dashboard' },
    { id: 'consultas', label: 'Consultas' },
    { id: 'configuracion', label: 'Configuracion' },
  ];

  showLanding(): void {
    this.view.set('landing');
    this.errorMessage.set('');
  }

  showLogin(): void {
    this.view.set('login');
    this.errorMessage.set('');
    this.statusMessage.set('Ingresa con tu cuenta de administrador para abrir el panel.');
  }

  showRegister(): void {
    this.view.set('register');
    this.errorMessage.set('');
    this.statusMessage.set('Crea la cuenta administradora de tu firma para abrir el panel.');
  }

  register(): void {
    const email = this.registerForm.email.trim().toLowerCase();
    const password = this.registerForm.password;
    const firmName = this.registerForm.firmName.trim();

    if (!email || !password || !firmName) {
      this.errorMessage.set('Completa email, password y nombre de la firma para registrarte.');
      return;
    }

    this.isSubmitting.set(true);
    this.errorMessage.set('');
    this.http
      .post<SessionResponse>(this.apiConfig.url('/api/auth/register'), {
        email,
        password,
        firmName,
      })
      .subscribe({
        next: (session) => {
          this.sessionEmail.set(session.email);
          this.tenantId.set(session.tenantId);
          this.view.set('console');
          this.activeConsoleSection.set('dashboard');
          this.isConsoleMenuOpen.set(false);
          this.statusMessage.set('Cuenta de administrador creada. Cargando datos de la firma...');
          this.registerForm.email = '';
          this.registerForm.password = '';
          this.registerForm.firmName = '';
          this.isSubmitting.set(false);
          this.loadConsultations();
          this.loadSettings();
        },
        error: () => {
          this.errorMessage.set('No se pudo crear la cuenta. Verifica los datos e intenta nuevamente.');
          this.isSubmitting.set(false);
        },
      });
  }

  login(): void {
    const email = this.loginForm.email.trim().toLowerCase();
    const password = this.loginForm.password;

    if (!email || !password) {
      this.errorMessage.set('Completa email y password para iniciar sesion.');
      return;
    }

    this.isSubmitting.set(true);
    this.errorMessage.set('');
    this.http
      .post<SessionResponse>(this.apiConfig.url('/api/auth/login'), {
        email,
        password,
      })
      .subscribe({
        next: (session) => {
          this.sessionEmail.set(session.email);
          this.tenantId.set(session.tenantId);
          this.view.set('console');
          this.activeConsoleSection.set('dashboard');
          this.isConsoleMenuOpen.set(false);
          this.statusMessage.set('Sesion iniciada. Cargando datos de la firma...');
          this.loginForm.email = '';
          this.loginForm.password = '';
          this.isSubmitting.set(false);
          this.loadConsultations();
          this.loadSettings();
        },
        error: (error: HttpErrorResponse) => {
          this.errorMessage.set(
            error.status === 401
              ? 'Credenciales invalidas. Verifica tu email y contrasena.'
              : 'No se pudo iniciar sesion. Intenta nuevamente.',
          );
          this.isSubmitting.set(false);
        },
      });
  }

  logout(): void {
    this.sessionEmail.set('');
    this.tenantId.set('');
    this.consultations.set([]);
    this.tenantSettings.set(null);
    this.isConsoleMenuOpen.set(false);
    this.showLanding();
  }

  toggleConsoleMenu(): void {
    this.isConsoleMenuOpen.update((isOpen) => !isOpen);
  }

  closeConsoleMenu(): void {
    this.isConsoleMenuOpen.set(false);
  }

  goToConsoleSection(section: ConsoleSection): void {
    this.activeConsoleSection.set(section);
    this.closeConsoleMenu();
    document.getElementById(section)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  @HostListener('window:scroll')
  updateActiveConsoleSection(): void {
    if (this.view() !== 'console') {
      return;
    }

    const threshold = 140;
    const visibleSection = this.consoleSections.reduce<ConsoleSection>((current, section) => {
      const element = document.getElementById(section.id);
      if (!element) {
        return current;
      }

      return element.getBoundingClientRect().top <= threshold ? section.id : current;
    }, 'dashboard');

    this.activeConsoleSection.set(visibleSection);
  }

  loadConsultations(): void {
    this.isLoading.set(true);
    this.errorMessage.set('');
    this.http
      .get<ConsultationListResponse>(
        this.apiConfig.url(`/api/admin/tenants/${this.tenantId()}/consultations`),
      )
      .subscribe({
        next: (response) => {
          this.consultations.set([...response.consultations].reverse());
          this.statusMessage.set('Datos de la firma cargados desde el servicio de intake.');
          this.isLoading.set(false);
        },
        error: () => {
          this.errorMessage.set('No se pudo conectar con el Sistema.');
          this.statusMessage.set('Revisa que el Sistema este activo.');
          this.isLoading.set(false);
        },
      });
  }

  loadSettings(): void {
    if (!this.tenantId()) {
      return;
    }

    this.http
      .get<TenantSettingsResponse>(
        this.apiConfig.url(`/api/tenants/${this.tenantId()}/settings`),
      )
      .subscribe({
        next: (settings) => {
          this.tenantSettings.set(settings);
          this.settingsForm.intakeEmail = settings.intakeEmail ?? '';
          this.settingsForm.destinationEmail = settings.destinationEmail ?? this.sessionEmail();
          this.settingsForm.urgentKeywords = settings.urgentKeywords.join(', ');
          this.settingsForm.consultationWindows = settings.consultationWindows.join(', ');
        },
        error: () => {
          this.errorMessage.set('No se pudo cargar la configuracion de intake.');
        },
      });
  }

  saveSettings(): void {
    const intakeEmail = this.settingsForm.intakeEmail.trim().toLowerCase();
    const destinationEmail = (this.settingsForm.destinationEmail.trim() || this.sessionEmail()).toLowerCase();

    if (!this.isValidEmail(intakeEmail)) {
      this.errorMessage.set('Ingresa un email de intake valido.');
      return;
    }

    if (!this.isValidEmail(destinationEmail)) {
      this.errorMessage.set('Ingresa un email de notificaciones valido.');
      return;
    }

    this.isSubmitting.set(true);
    this.errorMessage.set('');
    this.http
      .put<TenantSettingsResponse>(this.apiConfig.url(`/api/tenants/${this.tenantId()}/settings`), {
        urgentKeywords: this.csvValues(this.settingsForm.urgentKeywords),
        consultationWindows: this.csvValues(this.settingsForm.consultationWindows),
        destinationEmail,
        intakeEmail,
      })
      .subscribe({
        next: (settings) => {
          this.tenantSettings.set(settings);
          this.settingsForm.intakeEmail = settings.intakeEmail ?? '';
          this.settingsForm.destinationEmail = settings.destinationEmail ?? '';
          this.settingsForm.urgentKeywords = settings.urgentKeywords.join(', ');
          this.settingsForm.consultationWindows = settings.consultationWindows.join(', ');
          this.statusMessage.set('Email principal de intake actualizado.');
          this.isSubmitting.set(false);
        },
        error: (error: HttpErrorResponse) => {
          this.errorMessage.set(
            error.status === 409
              ? 'Ese email de intake ya esta configurado para otra firma.'
              : 'No se pudo guardar la configuracion. Intenta nuevamente.',
          );
          this.isSubmitting.set(false);
        },
      });
  }

  createConsultation(): void {
    if (
      !this.form.clientName.trim() ||
      !this.form.clientEmail.trim() ||
      !this.form.summary.trim()
    ) {
      this.errorMessage.set('Completa nombre, email y resumen para crear la consulta.');
      return;
    }

    this.isSubmitting.set(true);
    this.errorMessage.set('');
    this.http
      .post<Consultation>(this.apiConfig.url(`/api/tenants/${this.tenantId()}/consultations`), {
        clientName: this.form.clientName.trim(),
        clientEmail: this.form.clientEmail.trim(),
        summary: this.form.summary.trim(),
        preferredWindow: this.form.preferredWindow.trim(),
      })
      .subscribe({
        next: (consultation) => {
          this.consultations.update((items) => [consultation, ...items]);
          this.statusMessage.set('Consulta creada y enrutada para revision del equipo legal.');
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

  private csvValues(value: string): string[] {
    return value
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean);
  }

  private isValidEmail(value: string): boolean {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
  }
}
