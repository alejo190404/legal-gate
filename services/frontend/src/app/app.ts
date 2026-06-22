import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiConfigService } from './config/api-config.service';

type ViewName = 'landing' | 'login' | 'register' | 'console';
type ConsoleSection = 'dashboard' | 'consultas' | 'configuracion';

interface ClassificationResult {
  label: string;
  matchedUrgentKeywords: string[];
  concept: string | null;
  explanation: string;
  confidence: number | null;
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
  consultationType: string | null;
  assignedLawyerEmail: string | null;
  classification: ClassificationResult;
  notifications: NotificationStatus;
  sourceEventId: string | null;
  sourceMessageId: string | null;
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
  urgencyLevels: string[];
  destinationEmail: string | null;
  intakeEmail: string | null;
  routingRules: TenantRoutingRule[];
}

interface TenantRoutingRule {
  name: string;
  urgentKeywords: string[];
  consultationWindows: string[];
  destinationEmail: string | null;
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
  urgencyLevels: string;
  routingRules: TenantRoutingRuleForm[];
}

interface TenantRoutingRuleForm {
  name: string;
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
  readonly settingsLoaded = signal(false);
  readonly activeConsoleSection = signal<ConsoleSection>('dashboard');
  readonly isConsoleMenuOpen = signal(false);
  readonly isTutorialOpen = signal(false);
  readonly isLoading = signal(false);
  readonly isSubmitting = signal(false);
  readonly copyStatus = signal('');
  readonly statusMessage = signal('Listo para iniciar sesion.');
  readonly errorMessage = signal('');
  readonly consultationsErrorMessage = signal('');
  readonly settingsErrorMessage = signal('');

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
    urgencyLevels: 'NORMAL, URGENT',
    routingRules: [
      {
        name: 'Default intake route',
        destinationEmail: '',
        urgentKeywords: 'audiencia, captura, tutela, vencimiento',
        consultationWindows: '',
      },
    ],
  };

  readonly totalConsultations = computed(() => this.consultations().length);
  readonly urgentConsultations = computed(
    () => this.consultations().filter((consultation) => this.isHighestUrgency(consultation)).length,
  );
  readonly queuedNotifications = computed(
    () =>
      this.consultations().filter((consultation) => consultation.notifications.emailQueued).length,
  );
  readonly latestConsultation = computed(() => this.consultations()[0] ?? null);
  readonly configuredIntakeEmail = computed(
    () => {
      if (!this.settingsLoaded()) {
        return 'Cargando...';
      }
      return this.tenantSettings()?.intakeEmail || 'No disponible';
    },
  );
  readonly hasCanonicalIntakeEmail = computed(() => Boolean(this.tenantSettings()?.intakeEmail));
  readonly consoleSections: ReadonlyArray<{ id: ConsoleSection; label: string }> = [
    { id: 'dashboard', label: 'Dashboard' },
    { id: 'consultas', label: 'Consultas' },
    { id: 'configuracion', label: 'Configuracion' },
  ];

  showLanding(): void {
    this.view.set('landing');
    this.errorMessage.set('');
    this.clearConsoleErrors();
  }

  showLogin(): void {
    this.view.set('login');
    this.errorMessage.set('');
    this.clearConsoleErrors();
    this.statusMessage.set('Ingresa con tu cuenta de administrador para abrir el panel.');
  }

  showRegister(): void {
    this.view.set('register');
    this.errorMessage.set('');
    this.clearConsoleErrors();
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
          this.clearConsoleErrors();
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
          this.clearConsoleErrors();
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
    this.settingsLoaded.set(false);
    this.isTutorialOpen.set(false);
    this.copyStatus.set('');
    this.isConsoleMenuOpen.set(false);
    this.clearConsoleErrors();
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

  @HostListener('window:keydown.escape')
  closeTutorialWithEscape(): void {
    this.closeTutorial();
  }

  loadConsultations(): void {
    this.isLoading.set(true);
    this.consultationsErrorMessage.set('');
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
          this.consultationsErrorMessage.set('No se pudo conectar con el Sistema.');
          this.statusMessage.set('Revisa que el Sistema este activo.');
          this.isLoading.set(false);
        },
      });
  }

  loadSettings(): void {
    if (!this.tenantId()) {
      return;
    }

    this.settingsErrorMessage.set('');
    this.settingsLoaded.set(false);
    this.http
      .get<TenantSettingsResponse>(
        this.apiConfig.url(`/api/tenants/${this.tenantId()}/settings`),
      )
      .subscribe({
        next: (settings) => {
          this.tenantSettings.set(settings);
          this.settingsLoaded.set(true);
          this.settingsForm.routingRules = this.routingRuleFormsFrom(settings);
        },
        error: () => {
          this.settingsLoaded.set(true);
          this.settingsErrorMessage.set('No se pudo cargar la configuracion de intake.');
        },
      });
  }

  saveSettings(): void {
    const routingRules = this.settingsForm.routingRules.map((rule, index) => ({
      name: rule.name.trim() || `Route ${index + 1}`,
      urgentKeywords: this.csvValues(rule.urgentKeywords),
      consultationWindows: this.csvValues(rule.consultationWindows),
      destinationEmail: rule.destinationEmail.trim().toLowerCase(),
    }));
    const urgencyLevels = this.csvValues(this.settingsForm.urgencyLevels);

    if (!this.settingsLoaded() || !this.hasCanonicalIntakeEmail()) {
      this.settingsErrorMessage.set('Espera a que cargue el email LegalGate de intake.');
      return;
    }

    if (!urgencyLevels.length || urgencyLevels.length !== new Set(urgencyLevels).size) {
      this.settingsErrorMessage.set('Configura niveles de urgencia sin vacios ni duplicados.');
      return;
    }

    if (
      !routingRules.length ||
      routingRules.some((rule) => !this.isValidEmail(rule.destinationEmail))
    ) {
      this.settingsErrorMessage.set('Cada regla necesita un email de destino valido.');
      return;
    }

    this.isSubmitting.set(true);
    this.settingsErrorMessage.set('');
    this.http
      .put<TenantSettingsResponse>(this.apiConfig.url(`/api/tenants/${this.tenantId()}/settings`), {
        urgencyLevels,
        routingRules,
      })
      .subscribe({
        next: (settings) => {
          this.tenantSettings.set(settings);
          this.settingsForm.routingRules = this.routingRuleFormsFrom(settings);
          this.settingsErrorMessage.set('');
          this.statusMessage.set('Reglas de enrutamiento actualizadas.');
          this.isSubmitting.set(false);
        },
        error: (error: HttpErrorResponse) => {
          this.settingsErrorMessage.set(
            error.status === 409
              ? 'Ese email de intake ya esta configurado para otra firma.'
              : 'No se pudo guardar la configuracion. Intenta nuevamente.',
          );
          this.isSubmitting.set(false);
        },
      });
  }

  copyIntakeEmail(): void {
    const intakeEmail = this.tenantSettings()?.intakeEmail;
    if (!intakeEmail) {
      this.copyStatus.set('No disponible');
      return;
    }

    navigator.clipboard?.writeText(intakeEmail)
      .then(() => {
        this.copyStatus.set('Copiado');
        window.setTimeout(() => this.copyStatus.set(''), 1800);
      })
      .catch(() => {
        this.copyStatus.set('No se pudo copiar');
      }) ?? this.copyStatus.set('No se pudo copiar');
  }

  openTutorial(): void {
    this.isTutorialOpen.set(true);
  }

  closeTutorial(): void {
    this.isTutorialOpen.set(false);
  }

  addRoutingRule(): void {
    this.settingsForm.routingRules = [
      ...this.settingsForm.routingRules,
      {
        name: `Route ${this.settingsForm.routingRules.length + 1}`,
        destinationEmail: this.sessionEmail(),
        urgentKeywords: '',
        consultationWindows: '',
      },
    ];
  }

  removeRoutingRule(index: number): void {
    if (this.settingsForm.routingRules.length === 1) {
      this.settingsErrorMessage.set('Manten al menos una regla de enrutamiento.');
      return;
    }
    this.settingsErrorMessage.set('');
    this.settingsForm.routingRules = this.settingsForm.routingRules.filter(
      (_, itemIndex) => itemIndex !== index,
    );
  }

  createConsultation(): void {
    if (
      !this.form.clientName.trim() ||
      !this.form.clientEmail.trim() ||
      !this.form.summary.trim()
    ) {
      this.consultationsErrorMessage.set('Completa nombre, email y resumen para crear la consulta.');
      return;
    }

    this.isSubmitting.set(true);
    this.consultationsErrorMessage.set('');
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
          this.consultationsErrorMessage.set('');
          this.statusMessage.set('Consulta creada y enrutada para revision del equipo legal.');
          this.form.clientName = '';
          this.form.clientEmail = '';
          this.form.summary = '';
          this.form.preferredWindow = '';
          this.isSubmitting.set(false);
        },
        error: () => {
          this.consultationsErrorMessage.set('No se pudo crear la consulta. Intenta nuevamente.');
          this.isSubmitting.set(false);
        },
      });
  }

  urgencyLabel(consultation: Consultation): string {
    return consultation.urgency;
  }

  isHighestUrgency(consultation: Consultation): boolean {
    const levels = this.tenantSettings()?.urgencyLevels ?? ['NORMAL', 'URGENT'];
    return consultation.urgency === levels[levels.length - 1];
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

  private clearConsoleErrors(): void {
    this.consultationsErrorMessage.set('');
    this.settingsErrorMessage.set('');
  }

  private routingRuleFormsFrom(settings: TenantSettingsResponse): TenantRoutingRuleForm[] {
    this.settingsForm.urgencyLevels = (settings.urgencyLevels?.length
      ? settings.urgencyLevels
      : ['NORMAL', 'URGENT']
    ).join(', ');

    const rules =
      settings.routingRules?.length
        ? settings.routingRules
        : [
            {
              name: 'Default intake route',
              urgentKeywords: settings.urgentKeywords ?? [],
              consultationWindows: settings.consultationWindows ?? [],
              destinationEmail: settings.destinationEmail,
            },
          ];

    return rules.map((rule, index) => ({
      name: rule.name || `Route ${index + 1}`,
      destinationEmail: rule.destinationEmail ?? this.sessionEmail(),
      urgentKeywords: (rule.urgentKeywords ?? []).join(', '),
      consultationWindows: (rule.consultationWindows ?? []).join(', '),
    }));
  }
}
