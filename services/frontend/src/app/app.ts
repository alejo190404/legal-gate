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

interface EventDetails {
  id: string;
  lawyerId: string | null;
  lawyerDisplayName: string | null;
  lawyerEmail: string | null;
  routeName: string | null;
  urgencyName: string;
  slaDays: number;
  slaDeadline: string;
  priorityScore: number;
  scheduledStart: string | null;
  scheduledEnd: string | null;
  status: string;
  source: string;
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
  eventId: string | null;
  event: EventDetails | null;
}

interface ConsultationListResponse {
  tenantId: string;
  consultations: Consultation[];
}

interface UrgencyDefinition {
  name: string;
  rank: number;
  slaDays: number;
  active: boolean;
}

interface LawyerAvailabilityWindow {
  weekday: number;
  startTime: string;
  endTime: string;
  timezone: string;
}

interface LawyerProfile {
  id: string | null;
  displayName: string;
  email: string;
  active: boolean;
  defaultEventDurationMinutes: number;
  availabilityWindows: LawyerAvailabilityWindow[];
}

interface TenantSettingsResponse {
  tenantId: string;
  urgentKeywords: string[];
  consultationWindows: string[];
  urgencyLevels: string[];
  destinationEmail: string | null;
  intakeEmail: string | null;
  routingRules: TenantRoutingRule[];
  lawyers: LawyerProfile[];
}

interface TenantRoutingRule {
  name: string;
  description: string | null;
  urgentKeywords: string[];
  consultationWindows: string[];
  urgencyLevels: string[];
  lawyerId: string | null;
  urgencyDefinitions: UrgencyDefinition[];
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
  lawyers: LawyerForm[];
  routingRules: TenantRoutingRuleForm[];
}

interface LawyerForm {
  id: string | null;
  displayName: string;
  email: string;
  active: boolean;
  defaultEventDurationMinutes: number;
  availabilityWindows: LawyerAvailabilityWindow[];
}

interface TenantRoutingRuleForm {
  name: string;
  description: string;
  lawyerId: string | null;
  destinationEmail: string;
  urgentKeywords: string;
  consultationWindows: string;
  urgencyLevels: string;
  urgencyDefinitions: UrgencyDefinition[];
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
    lawyers: [this.blankLawyer()],
    routingRules: [
      {
        name: 'Default intake route',
        description: '',
        lawyerId: null,
        destinationEmail: '',
        urgentKeywords: 'audiencia, captura, tutela, vencimiento',
        consultationWindows: '',
        urgencyLevels: 'NORMAL, URGENT',
        urgencyDefinitions: [
          { name: 'NORMAL', rank: 1, slaDays: 5, active: true },
          { name: 'URGENT', rank: 2, slaDays: 1, active: true },
        ],
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
          this.settingsForm.lawyers = this.lawyerFormsFrom(settings);
          this.settingsForm.routingRules = this.routingRuleFormsFrom(settings);
        },
        error: () => {
          this.settingsLoaded.set(true);
          this.settingsErrorMessage.set('No se pudo cargar la configuracion de intake.');
        },
      });
  }

  saveSettings(): void {
    const lawyers = this.settingsForm.lawyers.map((lawyer) => ({
      id: lawyer.id,
      displayName: lawyer.displayName.trim(),
      email: lawyer.email.trim().toLowerCase(),
      active: lawyer.active,
      defaultEventDurationMinutes: Number(lawyer.defaultEventDurationMinutes),
      availabilityWindows: lawyer.availabilityWindows.map((window) => ({
        weekday: Number(window.weekday),
        startTime: window.startTime,
        endTime: window.endTime,
        timezone: window.timezone || 'America/Bogota',
      })),
    }));

    const primaryLawyerId = lawyers[0]?.id ?? null;
    const routingRules = this.settingsForm.routingRules.map((rule, index) => {
      const lawyer = lawyers.find((item) => item.id === rule.lawyerId) ?? lawyers[0];
      return {
        name: rule.name.trim() || `Route ${index + 1}`,
        description: rule.description.trim() || null,
        urgentKeywords: this.csvValues(rule.urgentKeywords),
        consultationWindows: this.csvValues(rule.consultationWindows),
        urgencyLevels: this.definitionsForSave(rule).filter((item) => item.active).sort((a, b) => a.rank - b.rank).map((item) => item.name.trim()),
        lawyerId: rule.lawyerId || primaryLawyerId,
        urgencyDefinitions: this.definitionsForSave(rule).map((item) => ({
          name: item.name.trim(),
          rank: Number(item.rank),
          slaDays: Number(item.slaDays),
          active: Boolean(item.active),
        })),
        destinationEmail: (lawyer?.email || rule.destinationEmail).trim().toLowerCase(),
      };
    });

    if (!this.settingsLoaded() || !this.hasCanonicalIntakeEmail()) {
      this.settingsErrorMessage.set('Espera a que cargue el email LegalGate de intake.');
      return;
    }

    if (!lawyers.length || !lawyers.some((lawyer) => lawyer.active) || lawyers.some((lawyer) => !lawyer.displayName || !this.isValidEmail(lawyer.email))) {
      this.settingsErrorMessage.set('Configura al menos un abogado activo con nombre y email valido.');
      return;
    }

    if (lawyers.some((lawyer) => !lawyer.defaultEventDurationMinutes || lawyer.defaultEventDurationMinutes < 15)) {
      this.settingsErrorMessage.set('La duracion por defecto debe ser de al menos 15 minutos.');
      return;
    }

    if (!routingRules.length || routingRules.some((rule) => !rule.lawyerId || !this.isValidEmail(rule.destinationEmail))) {
      this.settingsErrorMessage.set('Cada regla necesita un abogado asignado.');
      return;
    }

    if (routingRules.some((rule) => !this.hasValidUrgencyDefinitions(rule.urgencyDefinitions))) {
      this.settingsErrorMessage.set('Configura niveles de urgencia por regla sin vacios ni duplicados.');
      return;
    }

    this.isSubmitting.set(true);
    this.settingsErrorMessage.set('');
    this.http
      .put<TenantSettingsResponse>(this.apiConfig.url(`/api/tenants/${this.tenantId()}/settings`), {
        lawyers,
        routingRules,
      })
      .subscribe({
        next: (settings) => {
          this.tenantSettings.set(settings);
          this.settingsForm.lawyers = this.lawyerFormsFrom(settings);
          this.settingsForm.routingRules = this.routingRuleFormsFrom(settings);
          this.settingsErrorMessage.set('');
          this.statusMessage.set('Reglas, abogados y SLA actualizados.');
          this.isSubmitting.set(false);
        },
        error: (error: HttpErrorResponse) => {
          this.settingsErrorMessage.set(
            error.status === 409
              ? 'Ese email ya esta configurado para otra firma.'
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
    const lawyer = this.settingsForm.lawyers[0] ?? this.blankLawyer();
    this.settingsForm.routingRules = [
      ...this.settingsForm.routingRules,
      {
        name: `Route ${this.settingsForm.routingRules.length + 1}`,
        description: '',
        lawyerId: lawyer.id,
        destinationEmail: lawyer.email || this.sessionEmail(),
        urgentKeywords: '',
        consultationWindows: '',
        urgencyLevels: 'NORMAL, URGENT',
        urgencyDefinitions: [
          { name: 'NORMAL', rank: 1, slaDays: 5, active: true },
          { name: 'URGENT', rank: 2, slaDays: 1, active: true },
        ],
      },
    ];
  }

  removeRoutingRule(index: number): void {
    if (this.settingsForm.routingRules.length === 1) {
      this.settingsErrorMessage.set('Manten al menos una regla de enrutamiento.');
      return;
    }
    this.settingsErrorMessage.set('');
    this.settingsForm.routingRules = this.settingsForm.routingRules.filter((_, itemIndex) => itemIndex !== index);
  }

  addLawyer(): void {
    this.settingsForm.lawyers = [...this.settingsForm.lawyers, this.blankLawyer()];
  }

  removeLawyer(index: number): void {
    if (this.settingsForm.lawyers.length === 1) {
      this.settingsErrorMessage.set('Manten al menos un abogado.');
      return;
    }
    const removed = this.settingsForm.lawyers[index];
    this.settingsForm.lawyers = this.settingsForm.lawyers.filter((_, itemIndex) => itemIndex !== index);
    const fallback = this.settingsForm.lawyers[0];
    this.settingsForm.routingRules = this.settingsForm.routingRules.map((rule) =>
      rule.lawyerId === removed.id ? { ...rule, lawyerId: fallback.id, destinationEmail: fallback.email } : rule,
    );
  }

  addAvailabilityWindow(lawyer: LawyerForm): void {
    lawyer.availabilityWindows = [
      ...lawyer.availabilityWindows,
      { weekday: 1, startTime: '09:00', endTime: '17:00', timezone: 'America/Bogota' },
    ];
  }

  removeAvailabilityWindow(lawyer: LawyerForm, index: number): void {
    lawyer.availabilityWindows = lawyer.availabilityWindows.filter((_, itemIndex) => itemIndex !== index);
  }

  addUrgencyDefinition(rule: TenantRoutingRuleForm): void {
    const nextRank = rule.urgencyDefinitions.length + 1;
    rule.urgencyDefinitions = [
      ...rule.urgencyDefinitions,
      { name: `NIVEL ${nextRank}`, rank: nextRank, slaDays: 5, active: true },
    ];
  }

  removeUrgencyDefinition(rule: TenantRoutingRuleForm, index: number): void {
    if (rule.urgencyDefinitions.length === 1) {
      this.settingsErrorMessage.set('Manten al menos una urgencia por regla.');
      return;
    }
    rule.urgencyDefinitions = rule.urgencyDefinitions.filter((_, itemIndex) => itemIndex !== index);
  }

  lawyerSummary(lawyerId: string | null): string {
    const lawyer = this.settingsForm.lawyers.find((item) => item.id === lawyerId);
    if (!lawyer) {
      return 'Sin abogado asignado';
    }
    const windows = lawyer.availabilityWindows.length;
    return `${lawyer.displayName || lawyer.email} - ${windows} ventanas - ${lawyer.defaultEventDurationMinutes || 60} min`;
  }

  syncRouteLawyer(rule: TenantRoutingRuleForm): void {
    const lawyer = this.settingsForm.lawyers.find((item) => item.id === rule.lawyerId);
    if (lawyer) {
      rule.destinationEmail = lawyer.email;
    }
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
    return consultation.event?.urgencyName ?? consultation.urgency;
  }

  isHighestUrgency(consultation: Consultation): boolean {
    const settings = this.tenantSettings();
    const matchedRule = settings?.routingRules?.find((rule) => rule.name === consultation.consultationType);
    const definitions = matchedRule?.urgencyDefinitions?.filter((definition) => definition.active) ?? [];
    if (definitions.length) {
      const highest = [...definitions].sort((a, b) => a.rank - b.rank).at(-1);
      return consultation.urgency === highest?.name;
    }
    const levels = matchedRule?.urgencyLevels?.length
      ? matchedRule.urgencyLevels
      : (settings?.urgencyLevels?.length ? settings.urgencyLevels : ['NORMAL', 'URGENT']);
    return consultation.urgency === levels[levels.length - 1];
  }

  priorityLabel(consultation: Consultation): string {
    return consultation.event ? `P${consultation.event.priorityScore}` : 'Sin prioridad';
  }

  eventScheduleLabel(consultation: Consultation): string {
    if (!consultation.event) {
      return 'Sin evento';
    }
    if (!consultation.event.scheduledStart || !consultation.event.scheduledEnd) {
      return 'Requiere agenda manual';
    }
    return `${this.formatDate(consultation.event.scheduledStart)} - ${this.formatTime(consultation.event.scheduledEnd)}`;
  }

  formatDate(value: string): string {
    return new Intl.DateTimeFormat('es-CO', {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(value));
  }

  formatTime(value: string): string {
    return new Intl.DateTimeFormat('es-CO', { timeStyle: 'short' }).format(new Date(value));
  }

  private blankLawyer(): LawyerForm {
    const id = this.cryptoId();
    return {
      id,
      displayName: '',
      email: this.sessionEmail(),
      active: true,
      defaultEventDurationMinutes: 60,
      availabilityWindows: this.defaultAvailability(),
    };
  }

  private defaultAvailability(): LawyerAvailabilityWindow[] {
    return [1, 2, 3, 4, 5].map((weekday) => ({
      weekday,
      startTime: '09:00',
      endTime: '17:00',
      timezone: 'America/Bogota',
    }));
  }

  private lawyerFormsFrom(settings: TenantSettingsResponse): LawyerForm[] {
    const lawyers = settings.lawyers?.length
      ? settings.lawyers
      : [{
          id: this.cryptoId(),
          displayName: 'Abogado principal',
          email: settings.destinationEmail ?? this.sessionEmail(),
          active: true,
          defaultEventDurationMinutes: 60,
          availabilityWindows: this.defaultAvailability(),
        }];

    return lawyers.map((lawyer) => ({
      id: lawyer.id ?? this.cryptoId(),
      displayName: lawyer.displayName ?? '',
      email: lawyer.email ?? '',
      active: lawyer.active ?? true,
      defaultEventDurationMinutes: lawyer.defaultEventDurationMinutes ?? 60,
      availabilityWindows: lawyer.availabilityWindows?.length ? lawyer.availabilityWindows : this.defaultAvailability(),
    }));
  }

  private routingRuleFormsFrom(settings: TenantSettingsResponse): TenantRoutingRuleForm[] {
    const lawyers = this.settingsForm.lawyers.length ? this.settingsForm.lawyers : this.lawyerFormsFrom(settings);
    const fallbackLawyerId = lawyers[0]?.id ?? null;
    const rules = settings.routingRules?.length
      ? settings.routingRules
      : [{
          name: 'Default intake route',
          description: null,
          urgentKeywords: settings.urgentKeywords ?? [],
          consultationWindows: settings.consultationWindows ?? [],
          urgencyLevels: settings.urgencyLevels?.length ? settings.urgencyLevels : ['NORMAL', 'URGENT'],
          lawyerId: fallbackLawyerId,
          urgencyDefinitions: this.urgencyDefinitionsFromLevels(settings.urgencyLevels),
          destinationEmail: settings.destinationEmail,
        }];

    return rules.map((rule, index) => {
      const lawyerId = rule.lawyerId ?? this.lawyerIdForEmail(lawyers, rule.destinationEmail) ?? fallbackLawyerId;
      const lawyer = lawyers.find((item) => item.id === lawyerId);
      return {
        name: rule.name || `Route ${index + 1}`,
        description: rule.description ?? '',
        lawyerId,
        destinationEmail: rule.destinationEmail ?? lawyer?.email ?? this.sessionEmail(),
        urgentKeywords: (rule.urgentKeywords ?? []).join(', '),
        consultationWindows: (rule.consultationWindows ?? []).join(', '),
        urgencyLevels: (rule.urgencyLevels?.length ? rule.urgencyLevels : this.activeUrgencyNames(rule.urgencyDefinitions)).join(', '),
        urgencyDefinitions: rule.urgencyDefinitions?.length
          ? rule.urgencyDefinitions.map((item) => ({ ...item }))
          : this.urgencyDefinitionsFromLevels(rule.urgencyLevels),
      };
    });
  }

  private definitionsForSave(rule: TenantRoutingRuleForm): UrgencyDefinition[] {
    return rule.urgencyDefinitions;
  }

  private activeUrgencyNames(definitions: UrgencyDefinition[] | undefined): string[] {
    return (definitions ?? [])
      .filter((definition) => definition.active)
      .sort((a, b) => a.rank - b.rank)
      .map((definition) => definition.name);
  }

  private urgencyDefinitionsFromLevels(levels: string[] | undefined): UrgencyDefinition[] {
    const names = levels?.length ? levels : ['NORMAL', 'URGENT'];
    return names.map((name, index) => ({
      name,
      rank: index + 1,
      slaDays: name.toUpperCase() === 'URGENT' ? 1 : 5,
      active: true,
    }));
  }

  private lawyerIdForEmail(lawyers: LawyerForm[], email: string | null): string | null {
    if (!email) {
      return null;
    }
    return lawyers.find((lawyer) => lawyer.email.toLowerCase() === email.toLowerCase())?.id ?? null;
  }

  private hasValidUrgencyDefinitions(definitions: UrgencyDefinition[]): boolean {
    const active = definitions.filter((definition) => definition.active);
    const names = definitions.map((definition) => definition.name.trim().toLowerCase()).filter(Boolean);
    return Boolean(active.length)
      && names.length === definitions.length
      && names.length === new Set(names).size
      && definitions.every((definition) => Number(definition.rank) > 0 && Number(definition.slaDays) >= 0);
  }

  private csvValues(value: string): string[] {
    return value
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean);
  }

  private cryptoId(): string {
    return crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
  }

  private isValidEmail(value: string): boolean {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
  }

  private clearConsoleErrors(): void {
    this.consultationsErrorMessage.set('');
    this.settingsErrorMessage.set('');
  }
}
