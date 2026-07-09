import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../auth/auth.service';
import { ApiConfigService } from '../config/api-config.service';
import { LoadingScreenComponent } from '../loading-screen/loading-screen';
import { PixelIconComponent } from './pixel-icon';

type ViewName = 'loading' | 'register' | 'onboard-lawyers' | 'onboard-rules' | 'billing' | 'console';
type ConsoleView =
  | 'inbox'
  | 'rules'
  | 'calendar'
  | 'templates'
  | 'team'
  | 'availability'
  | 'settings'
  | 'billing';

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
  meetingUrl: string | null;
  scheduledWithinSla: boolean | null;
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
  meetingUrl: string | null;
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

interface RegisterForm {
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
  meetingUrl: string;
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
  userId: string;
  sessionId: string;
  organizationId: string;
  tenantId: string;
  displayName: string;
  role: string;
}

interface OrganizationOnboardingResponse {
  organizationId: string;
  tenantId: string;
  displayName: string;
  status: string;
}

interface BillingPlan {
  id: string;
  code: string;
  version: number;
  displayName: string;
  description: string | null;
  interval: 'MONTHLY' | 'YEARLY';
  priceCop: number;
  displayOrder: number;
}

interface BillingQuote {
  plan: BillingPlan;
  couponCode: string | null;
  originalAmountCop: number;
  recurringAmountCop: number;
  discountAmountCop: number;
  currency: string;
  couponDuration: string | null;
  discountedCycles: number | null;
}

interface BillingPayment {
  providerPaymentId: string;
  status: string;
  amountCop: number | null;
  paidAt: string | null;
  periodStart: string | null;
  periodEnd: string | null;
}

interface BillingSubscription {
  id: string;
  plan: BillingPlan;
  coupon: { code: string; duration: string } | null;
  originalAmountCop: number;
  currentAmountCop: number;
  status: string;
  providerStatus: string | null;
  paidThrough: string | null;
  graceDeadline: string | null;
  canceledAt: string | null;
  cancelAtPeriodEnd: boolean;
}

interface BillingStatus {
  billingEnabled: boolean;
  enforcementEnabled: boolean;
  entitled: boolean;
  status: string;
  subscription: BillingSubscription | null;
  payments: BillingPayment[];
  accessEndsAt: string | null;
  message: string;
}

interface BillingCheckoutAttempt {
  tenantId: string;
  planCode: string;
  couponCode: string;
  idempotencyKey: string;
}

// Pre-checkout onboarding: dashed inbox→lawyer connector and one animated
// envelope traveling along it.
interface RouteConnector {
  lawyerId: string;
  d: string;
}

interface EnvelopeFlight {
  id: number;
  lawyerId: string;
  d: string;
}

interface CalendarBlock {
  topPct: number;
  heightPct: number;
  label: string;
}

@Component({
  selector: 'app-console',
  imports: [FormsModule, LoadingScreenComponent, PixelIconComponent],
  templateUrl: './console.html',
  styleUrl: './console.scss',
})
export class ConsoleComponent implements OnInit, OnDestroy {
  private static readonly checkoutAttemptStorageKey = 'legalgate-active-checkout';
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfigService);
  readonly auth = inject(AuthService);
  private billingReturnPollHandle: number | null = null;
  private isDestroyed = false;

  // The guard guarantees an authenticated user, so the console opens on the
  // branded loading screen while the session and billing calls resolve.
  readonly view = signal<ViewName>('loading');
  readonly tenantId = signal('');
  readonly sessionEmail = signal('');
  readonly consultations = signal<Consultation[]>([]);
  readonly tenantSettings = signal<TenantSettingsResponse | null>(null);
  readonly settingsLoaded = signal(false);
  readonly activeView = signal<ConsoleView>('inbox');
  readonly userDisplayName = signal('');
  readonly inboxFilter = signal<string>('all');
  readonly inboxQuery = signal('');
  readonly selectedConsultationId = signal<string | null>(null);
  readonly isConsoleMenuOpen = signal(false);
  readonly isCreateOpen = signal(false);
  readonly isTutorialOpen = signal(false);
  readonly isLoading = signal(false);
  readonly isSubmitting = signal(false);
  readonly copyStatus = signal('');
  readonly statusMessage = signal('Listo para iniciar sesion.');
  readonly errorMessage = signal('');
  readonly consultationsErrorMessage = signal('');
  readonly settingsErrorMessage = signal('');
  readonly activeLawyerIndex = signal(0);
  readonly activeRuleIndex = signal(0);
  readonly billingPlans = signal<BillingPlan[]>([]);
  readonly billingStatus = signal<BillingStatus | null>(null);
  readonly billingQuote = signal<BillingQuote | null>(null);
  readonly selectedPlanCode = signal('');
  readonly couponCode = signal('');
  readonly billingMessage = signal('');
  readonly billingError = signal('');
  readonly isBillingLoading = signal(false);

  // --- Pre-checkout onboarding (lawyers + rules before payment) ---
  static readonly onboardingLawyerLimit = 3;
  @ViewChild('routeDiagram') routeDiagram?: ElementRef<HTMLElement>;
  readonly onboardingLawyers = signal<LawyerForm[]>([]);
  readonly onboardingRules = signal<TenantRoutingRuleForm[]>([]);
  readonly onboardingError = signal('');
  readonly routeConnectors = signal<RouteConnector[]>([]);
  readonly envelopeFlights = signal<EnvelopeFlight[]>([]);
  readonly demoDeliveries = signal<Record<string, number>>({});
  readonly diagramSize = signal<{ width: number; height: number }>({ width: 0, height: 0 });
  lawyerDraft: LawyerForm = this.blankLawyer();
  ruleDraft: TenantRoutingRuleForm = this.blankRuleDraft();
  private demoLoopHandle: number | null = null;
  private demoRuleCursor = 0;
  private flightSeq = 0;
  readonly onboardingLawyerLimitReached = computed(
    () => this.onboardingLawyers().length >= ConsoleComponent.onboardingLawyerLimit,
  );

  readonly registerForm: RegisterForm = {
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
  readonly configuredIntakeEmail = computed(() => {
    if (!this.settingsLoaded()) {
      return 'Cargando...';
    }
    return this.tenantSettings()?.intakeEmail || 'No disponible';
  });
  readonly hasCanonicalIntakeEmail = computed(() => Boolean(this.tenantSettings()?.intakeEmail));
  // App-kit navigation: a Workspace group and a Configure group, mirroring the
  // "Firm Console" in Claude Design. `wip` screens render the design-system
  // Work-in-progress placeholder instead of a built feature.
  readonly workspaceNav: ReadonlyArray<{ id: ConsoleView; icon: string; label: string; wip?: boolean }> = [
    { id: 'inbox', icon: 'inbox', label: 'Bandeja de intake' },
    { id: 'rules', icon: 'route', label: 'Reglas de enrutamiento' },
    { id: 'templates', icon: 'mail', label: 'Plantillas de correo', wip: true },
  ];
  readonly configureNav: ReadonlyArray<{ id: ConsoleView; icon: string; label: string; wip?: boolean }> = [
    { id: 'team', icon: 'users', label: 'Equipo' },
    { id: 'settings', icon: 'gear', label: 'Ajustes' },
    { id: 'billing', icon: 'file', label: 'Facturacion' },
  ];

  readonly statusChips: ReadonlyArray<{ id: string; label: string }> = [
    { id: 'all', label: 'Todas' },
    { id: 'new', label: 'Nuevas' },
    { id: 'classified', label: 'Clasificadas' },
    { id: 'scheduled', label: 'Agendadas' },
    { id: 'confirmed', label: 'Confirmadas' },
    { id: 'unrouted', label: 'Sin ruta' },
  ];

  readonly tenCells = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9];

  private readonly viewMeta: Record<ConsoleView, { crumb: string; title: string }> = {
    inbox: { crumb: 'Espacio de trabajo', title: 'Bandeja de intake' },
    rules: { crumb: 'Configurar', title: 'Reglas de enrutamiento' },
    calendar: { crumb: 'Espacio de trabajo', title: 'Consultas' },
    templates: { crumb: 'Configurar', title: 'Plantillas de correo' },
    team: { crumb: 'Configurar', title: 'Equipo' },
    availability: { crumb: 'Configurar', title: 'Disponibilidad' },
    settings: { crumb: 'Configurar', title: 'Ajustes' },
    billing: { crumb: 'Configurar', title: 'Facturacion' },
  };

  readonly filteredConsultations = computed(() => {
    const filter = this.inboxFilter();
    const query = this.inboxQuery().trim().toLowerCase();
    let list = this.consultations();
    if (filter !== 'all') {
      list = list.filter((consultation) => this.statusKey(consultation) === filter);
    }
    if (query) {
      list = list.filter((consultation) =>
        [consultation.clientName, consultation.clientEmail, consultation.id, consultation.summary]
          .join(' ')
          .toLowerCase()
          .includes(query),
      );
    }
    return list;
  });

  readonly selectedConsultation = computed(() => {
    const id = this.selectedConsultationId();
    return id ? (this.consultations().find((consultation) => consultation.id === id) ?? null) : null;
  });

  ngOnInit(): void {
    const user = this.auth.user();
    if (!user) {
      return;
    }
    this.sessionEmail.set(user.email);
    if (this.auth.hasOrganization()) {
      this.view.set('loading');
      this.loadSession();
    } else {
      this.view.set('register');
      this.statusMessage.set('Completa el nombre de tu firma para terminar la configuracion.');
    }
  }

  ngOnDestroy(): void {
    this.isDestroyed = true;
    // ConsoleComponent is now routable, so cancel the billing-return poll if the
    // router tears it down while a poll is still pending.
    this.clearBillingReturnPoll();
    this.stopDemoLoop();
  }

  register(): void {
    const firmName = this.registerForm.firmName.trim();

    if (!firmName) {
      this.errorMessage.set('Ingresa el nombre de la firma.');
      return;
    }

    this.isSubmitting.set(true);
    this.errorMessage.set('');
    this.http
      .post<OrganizationOnboardingResponse>(
        this.apiConfig.url('/api/onboarding/organization'),
        { firmName },
      )
      .subscribe({
        next: (organization) => {
          this.registerForm.firmName = '';
          void this.auth
            .switchToOrganization(organization.organizationId)
            .then(() => this.loadSession())
            .catch(() => {
              this.errorMessage.set(
                'La firma fue creada. Vuelve a iniciar sesion para activar la organizacion.',
              );
              this.isSubmitting.set(false);
            });
        },
        error: () => {
          this.errorMessage.set(
            'No se pudo configurar la firma. Verifica los datos e intenta nuevamente.',
          );
          this.isSubmitting.set(false);
        },
      });
  }

  logout(): void {
    this.clearBillingReturnPoll();
    this.sessionEmail.set('');
    this.tenantId.set('');
    this.consultations.set([]);
    this.tenantSettings.set(null);
    this.settingsLoaded.set(false);
    this.activeLawyerIndex.set(0);
    this.activeRuleIndex.set(0);
    this.isTutorialOpen.set(false);
    this.copyStatus.set('');
    this.billingPlans.set([]);
    this.billingStatus.set(null);
    this.billingQuote.set(null);
    this.billingMessage.set('');
    this.billingError.set('');
    this.clearCheckoutAttempt();
    this.stopDemoLoop();
    this.onboardingLawyers.set([]);
    this.onboardingRules.set([]);
    this.onboardingError.set('');
    this.routeConnectors.set([]);
    this.envelopeFlights.set([]);
    this.demoDeliveries.set({});
    this.isConsoleMenuOpen.set(false);
    this.activeView.set('inbox');
    this.selectedConsultationId.set(null);
    this.inboxFilter.set('all');
    this.isCreateOpen.set(false);
    this.clearConsoleErrors();
    this.auth.signOut();
  }

  loadSession(): void {
    this.view.set('loading');
    this.errorMessage.set('');
    this.isLoading.set(true);
    this.http.get<SessionResponse>(this.apiConfig.url('/api/session')).subscribe({
      next: (session) => {
        this.tenantId.set(session.tenantId);
        this.sessionEmail.set(this.auth.user()?.email ?? '');
        this.userDisplayName.set(session.displayName ?? '');
        this.activeView.set('inbox');
        this.selectedConsultationId.set(null);
        this.inboxFilter.set('all');
        this.isConsoleMenuOpen.set(false);
        this.clearConsoleErrors();
        this.statusMessage.set('Sesion segura iniciada. Validando suscripcion...');
        this.isSubmitting.set(false);
        this.isLoading.set(false);
        this.loadBillingStatus(true);
      },
      error: (error: HttpErrorResponse) => {
        this.isLoading.set(false);
        this.isSubmitting.set(false);
        if (error.status === 403 && !this.auth.hasOrganization()) {
          this.view.set('register');
          return;
        }
        this.errorMessage.set('No se pudo cargar la sesion de la firma.');
      },
    });
  }

  loadBillingStatus(initial = false): void {
    this.isBillingLoading.set(true);
    this.billingError.set('');
    this.http
      .get<BillingStatus>(this.apiConfig.url('/api/billing/subscription'))
      .subscribe({
        next: (status) => {
          this.billingStatus.set(status);
          this.isBillingLoading.set(false);
          if (!status.billingEnabled || status.subscription?.status !== 'PENDING') {
            this.clearCheckoutAttempt();
          }
          if (!status.billingEnabled || status.entitled) {
            const wasOutsideConsole = this.view() !== 'console';
            this.view.set('console');
            if (wasOutsideConsole || initial) {
              this.loadConsultations();
              this.loadSettings();
            }
          } else {
            this.clearProtectedTenantData();
            const returningFromCheckout =
              new URLSearchParams(window.location.search).get('billing') === 'return';
            // Unpaid firms configure lawyers + routing rules first, so checkout is
            // not the first thing they see. Checkout returns skip straight to
            // billing so the payment poll keeps working.
            if (initial && !returningFromCheckout) {
              this.startOnboarding();
            } else {
              this.view.set('billing');
              this.loadBillingPlans();
            }
          }
          if (new URLSearchParams(window.location.search).get('billing') === 'return') {
            if (status.entitled) {
              window.history.replaceState({}, '', window.location.pathname);
              this.billingMessage.set('Pago confirmado. Tu acceso ya esta activo.');
            } else if (initial) {
              this.pollBillingReturn();
            }
          }
        },
        error: () => {
          this.isBillingLoading.set(false);
          this.billingError.set('No se pudo consultar el estado de facturacion.');
          if (initial) this.view.set('billing');
        },
      });
  }

  loadBillingPlans(): void {
    this.http.get<BillingPlan[]>(this.apiConfig.url('/api/billing/plans')).subscribe({
      next: (plans) => {
        this.billingPlans.set(plans);
        const selected = this.selectedPlanCode();
        if (!plans.some((plan) => plan.code === selected)) {
          this.selectedPlanCode.set(plans[0]?.code ?? '');
          this.billingQuote.set(null);
        }
      },
      error: () => this.billingError.set('No se pudieron cargar los planes disponibles.'),
    });
  }

  selectBillingPlan(code: string): void {
    this.selectedPlanCode.set(code);
    this.billingQuote.set(null);
    this.billingError.set('');
  }

  quoteBilling(): void {
    if (!this.selectedPlanCode()) return;
    this.isBillingLoading.set(true);
    this.billingError.set('');
    this.http
      .post<BillingQuote>(this.apiConfig.url('/api/billing/quote'), {
        planCode: this.selectedPlanCode(),
        couponCode: this.couponCode().trim() || null,
      })
      .subscribe({
        next: (quote) => {
          this.billingQuote.set(quote);
          this.isBillingLoading.set(false);
        },
        error: () => {
          this.billingQuote.set(null);
          this.billingError.set('El plan o cupon no es valido.');
          this.isBillingLoading.set(false);
        },
      });
  }

  checkoutBilling(): void {
    if (!this.selectedPlanCode()) return;
    this.isBillingLoading.set(true);
    this.billingError.set('');
    const planCode = this.selectedPlanCode();
    const couponCode = this.couponCode().trim();
    const activeAttempt = this.checkoutAttempt();
    const idempotencyKey =
      activeAttempt?.tenantId === this.tenantId() &&
      activeAttempt.planCode === planCode &&
      activeAttempt.couponCode === couponCode
        ? activeAttempt.idempotencyKey
        : this.cryptoId();
    this.storeCheckoutAttempt({ tenantId: this.tenantId(), planCode, couponCode, idempotencyKey });
    this.http
      .post<{ checkoutUrl: string }>(
        this.apiConfig.url('/api/billing/checkout'),
        {
          planCode,
          couponCode: couponCode || null,
        },
        { headers: { 'Idempotency-Key': idempotencyKey } },
      )
      .subscribe({
        next: (checkout) => {
          if (!checkout.checkoutUrl) {
            this.billingError.set('Mercado Pago aun esta preparando el checkout. Intenta nuevamente.');
            this.isBillingLoading.set(false);
            return;
          }
          window.location.assign(checkout.checkoutUrl);
        },
        error: (error: HttpErrorResponse) => {
          if (error.status >= 400 && error.status < 500) {
            this.clearCheckoutAttempt();
          }
          this.billingError.set('No se pudo iniciar Mercado Pago. El intento se puede reanudar sin duplicar la suscripcion.');
          this.isBillingLoading.set(false);
        },
      });
  }

  cancelBilling(): void {
    this.isBillingLoading.set(true);
    this.billingError.set('');
    this.http
      .post<BillingStatus>(this.apiConfig.url('/api/billing/subscription/cancel'), {})
      .subscribe({
        next: (status) => {
          this.billingStatus.set(status);
          this.billingMessage.set(
            status.accessEndsAt
              ? `Renovacion cancelada. El acceso continua hasta ${this.formatDate(status.accessEndsAt)}.`
              : 'Renovacion cancelada.',
          );
          this.isBillingLoading.set(false);
        },
        error: () => {
          this.billingError.set('No se pudo cancelar la renovacion.');
          this.isBillingLoading.set(false);
        },
      });
  }

  formatCop(value: number | null | undefined): string {
    if (value == null) return '—';
    return new Intl.NumberFormat('es-CO', {
      style: 'currency',
      currency: 'COP',
      maximumFractionDigits: 0,
    }).format(value);
  }

  private pollBillingReturn(attempt = 0): void {
    if (this.isDestroyed || !this.tenantId()) {
      this.clearBillingReturnPoll();
      return;
    }
    if (attempt >= 30 || this.billingStatus()?.entitled) {
      this.clearBillingReturnPoll();
      if (!this.billingStatus()?.entitled) {
        this.billingMessage.set('El pago sigue pendiente. Puedes actualizar el estado en unos minutos.');
      }
      return;
    }
    this.clearBillingReturnPoll();
    this.billingReturnPollHandle = window.setTimeout(() => {
      if (this.isDestroyed || !this.tenantId()) return;
      if (!this.tenantId()) return;
      this.http
        .get<BillingStatus>(this.apiConfig.url('/api/billing/subscription'))
        .subscribe({
          next: (status) => {
            if (this.isDestroyed || !this.tenantId()) return;
            this.billingStatus.set(status);
            if (status.entitled) {
              this.clearCheckoutAttempt();
              window.history.replaceState({}, '', window.location.pathname);
              this.billingMessage.set('Pago confirmado. Tu acceso ya esta activo.');
              this.view.set('console');
              this.loadConsultations();
              this.loadSettings();
            } else {
              this.pollBillingReturn(attempt + 1);
            }
          },
          error: () => {
            if (!this.isDestroyed && this.tenantId()) this.pollBillingReturn(attempt + 1);
          },
        });
    }, 2000);
  }

  private clearBillingReturnPoll(): void {
    if (this.billingReturnPollHandle == null) return;
    window.clearTimeout(this.billingReturnPollHandle);
    this.billingReturnPollHandle = null;
  }

  private clearProtectedTenantData(): void {
    this.consultations.set([]);
    this.tenantSettings.set(null);
    this.settingsLoaded.set(false);
  }

  private handleProtectedError(error: HttpErrorResponse, fallback: () => void): void {
    if (error.status === 402) {
      this.isLoading.set(false);
      this.isSubmitting.set(false);
      this.clearProtectedTenantData();
      this.view.set('billing');
      this.loadBillingStatus();
      return;
    }
    fallback();
  }

  toggleConsoleMenu(): void {
    this.isConsoleMenuOpen.update((isOpen) => !isOpen);
  }

  closeConsoleMenu(): void {
    this.isConsoleMenuOpen.set(false);
  }

  setView(view: ConsoleView): void {
    this.activeView.set(view);
    this.closeConsoleMenu();
  }

  viewTitle(): string {
    return this.viewMeta[this.activeView()].title;
  }

  viewCrumb(): string {
    return this.viewMeta[this.activeView()].crumb;
  }

  navCount(id: ConsoleView): number | null {
    switch (id) {
      case 'inbox':
        return this.totalConsultations();
      case 'rules':
        return this.settingsForm.routingRules.length;
      case 'team':
        return this.settingsForm.lawyers.length;
      default:
        return null;
    }
  }

  selectConsultation(id: string): void {
    this.selectedConsultationId.set(id);
  }

  closeDetail(): void {
    this.selectedConsultationId.set(null);
  }

  setInboxFilter(id: string): void {
    this.inboxFilter.set(id);
  }

  statusCount(id: string): number {
    const all = this.consultations();
    return id === 'all' ? all.length : all.filter((consultation) => this.statusKey(consultation) === id).length;
  }

  // Derive one of the design-system workflow stages from the real consultation
  // fields (event schedule + assignment + classification confidence).
  statusKey(consultation: Consultation): string {
    const event = consultation.event;
    const eventStatus = (event?.status ?? '').toUpperCase();
    const consultationStatus = (consultation.status ?? '').toUpperCase();
    if (event?.scheduledStart) {
      return event.scheduledWithinSla === true ||
        eventStatus.includes('CONFIRM') ||
        consultationStatus.includes('CONFIRM')
        ? 'confirmed'
        : 'scheduled';
    }
    if (event?.lawyerDisplayName || event?.lawyerEmail || consultation.assignedLawyerEmail) {
      return 'classified';
    }
    const confidence = consultation.classification?.confidence;
    if (confidence != null && confidence < 0.5) {
      return 'unrouted';
    }
    if (consultationStatus.includes('UNROUT') || consultationStatus.includes('FAIL')) {
      return 'unrouted';
    }
    return 'new';
  }

  badgeClass(consultation: Consultation): string {
    return 'badge st-' + this.statusKey(consultation);
  }

  statusLabel(consultation: Consultation): string {
    return (
      {
        new: 'Nuevo',
        classified: 'Clasificado',
        scheduled: 'Agendado',
        confirmed: 'Confirmado',
        unrouted: 'Sin ruta',
      }[this.statusKey(consultation)] ?? 'Nuevo'
    );
  }

  leadLawyer(consultation: Consultation): string | null {
    return (
      consultation.event?.lawyerDisplayName ||
      consultation.event?.lawyerEmail ||
      consultation.assignedLawyerEmail ||
      null
    );
  }

  confidencePercent(consultation: Consultation): number | null {
    const value = consultation.classification?.confidence;
    return value == null ? null : Math.round(value * 100);
  }

  confidenceFilled(consultation: Consultation): number {
    const value = consultation.classification?.confidence;
    return value == null ? 0 : Math.round(value * 10);
  }

  confidenceLow(consultation: Consultation): boolean {
    const value = consultation.classification?.confidence;
    return value != null && value < 0.5;
  }

  initials(value: string | null | undefined): string {
    const parts = (value ?? '').trim().split(/\s+/).filter(Boolean);
    if (!parts.length) {
      return '—';
    }
    if (parts.length === 1) {
      return parts[0].slice(0, 2).toUpperCase();
    }
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  }

  openCreate(): void {
    this.isCreateOpen.set(true);
  }

  closeCreate(): void {
    this.isCreateOpen.set(false);
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
        this.apiConfig.url('/api/consultations'),
      )
      .subscribe({
        next: (response) => {
          this.consultations.set([...response.consultations].reverse());
          this.statusMessage.set('Datos de la firma cargados desde el servicio de intake.');
          this.isLoading.set(false);
        },
        error: (error: HttpErrorResponse) =>
          this.handleProtectedError(error, () => {
            this.consultationsErrorMessage.set('No se pudo conectar con el Sistema.');
            this.statusMessage.set('Revisa que el Sistema este activo.');
            this.isLoading.set(false);
          }),
      });
  }

  loadSettings(): void {
    if (!this.tenantId()) {
      return;
    }

    this.settingsErrorMessage.set('');
    this.settingsLoaded.set(false);
    this.http
      .get<TenantSettingsResponse>(this.apiConfig.url('/api/tenant/settings'))
      .subscribe({
        next: (settings) => {
          this.tenantSettings.set(settings);
          this.settingsLoaded.set(true);
          this.settingsForm.lawyers = this.lawyerFormsFrom(settings);
          this.settingsForm.routingRules = this.routingRuleFormsFrom(settings);
          this.clampActiveIndexes();
        },
        error: (error: HttpErrorResponse) =>
          this.handleProtectedError(error, () => {
            this.settingsLoaded.set(true);
            this.settingsErrorMessage.set('No se pudo cargar la configuracion de intake.');
          }),
      });
  }

  private lawyersPayload(forms: LawyerForm[]) {
    return forms.map((lawyer) => ({
      id: lawyer.id,
      displayName: lawyer.displayName.trim(),
      email: lawyer.email.trim().toLowerCase(),
      meetingUrl: lawyer.meetingUrl.trim() || null,
      active: lawyer.active,
      defaultEventDurationMinutes: Number(lawyer.defaultEventDurationMinutes),
      availabilityWindows: lawyer.availabilityWindows.map((window) => ({
        weekday: Number(window.weekday),
        startTime: window.startTime,
        endTime: window.endTime,
        timezone: 'America/Bogota',
      })),
    }));
  }

  private rulesPayload(
    forms: TenantRoutingRuleForm[],
    lawyers: ReturnType<ConsoleComponent['lawyersPayload']>,
  ) {
    const primaryLawyerId = lawyers[0]?.id ?? null;
    return forms.map((rule, index) => {
      const lawyer = lawyers.find((item) => item.id === rule.lawyerId) ?? lawyers[0];
      const urgencyDefinitions = this.definitionsForSave(rule);
      return {
        name: rule.name.trim() || `Route ${index + 1}`,
        description: rule.description.trim() || null,
        urgentKeywords: this.csvValues(rule.urgentKeywords),
        consultationWindows: this.csvValues(rule.consultationWindows),
        urgencyLevels: urgencyDefinitions.map((item) => item.name.trim()),
        lawyerId: rule.lawyerId || primaryLawyerId,
        urgencyDefinitions: urgencyDefinitions.map((item) => ({
          name: item.name.trim(),
          rank: Number(item.rank),
          slaDays: Number(item.slaDays),
          active: true,
        })),
        destinationEmail: (lawyer?.email || rule.destinationEmail).trim().toLowerCase(),
      };
    });
  }

  private settingsPayloadError(
    lawyers: ReturnType<ConsoleComponent['lawyersPayload']>,
    routingRules: ReturnType<ConsoleComponent['rulesPayload']>,
  ): string | null {
    if (
      !lawyers.length ||
      !lawyers.some((lawyer) => lawyer.active) ||
      lawyers.some((lawyer) => !lawyer.displayName || !this.isValidEmail(lawyer.email))
    ) {
      return 'Configura al menos un abogado activo con nombre y email valido.';
    }
    if (
      lawyers.some(
        (lawyer) => !lawyer.defaultEventDurationMinutes || lawyer.defaultEventDurationMinutes < 15,
      )
    ) {
      return 'La duracion por defecto debe ser de al menos 15 minutos.';
    }
    if (
      !routingRules.length ||
      routingRules.some((rule) => !rule.lawyerId || !this.isValidEmail(rule.destinationEmail))
    ) {
      return 'Cada regla necesita un abogado asignado.';
    }
    if (routingRules.some((rule) => !this.hasValidUrgencyDefinitions(rule.urgencyDefinitions))) {
      return 'Configura niveles de urgencia por regla sin vacios ni duplicados.';
    }
    return null;
  }

  saveSettings(): void {
    const lawyers = this.lawyersPayload(this.settingsForm.lawyers);
    const routingRules = this.rulesPayload(this.settingsForm.routingRules, lawyers);

    if (!this.settingsLoaded() || !this.hasCanonicalIntakeEmail()) {
      this.settingsErrorMessage.set('Espera a que cargue el email LegalGate de intake.');
      return;
    }

    const validationError = this.settingsPayloadError(lawyers, routingRules);
    if (validationError) {
      this.settingsErrorMessage.set(validationError);
      return;
    }

    this.isSubmitting.set(true);
    this.settingsErrorMessage.set('');
    this.http
      .put<TenantSettingsResponse>(this.apiConfig.url('/api/tenant/settings'), {
        lawyers,
        routingRules,
      })
      .subscribe({
        next: (settings) => {
          this.tenantSettings.set(settings);
          this.settingsForm.lawyers = this.lawyerFormsFrom(settings);
          this.settingsForm.routingRules = this.routingRuleFormsFrom(settings);
          this.clampActiveIndexes();
          this.settingsErrorMessage.set('');
          this.statusMessage.set('Reglas, abogados y SLA actualizados.');
          this.isSubmitting.set(false);
        },
        error: (error: HttpErrorResponse) => {
          this.handleProtectedError(error, () => {
            this.settingsErrorMessage.set(
              error.status === 409
                ? 'Ese email ya esta configurado para otra firma.'
                : 'No se pudo guardar la configuracion. Intenta nuevamente.',
            );
            this.isSubmitting.set(false);
          });
        },
      });
  }
  copyIntakeEmail(): void {
    const intakeEmail = this.tenantSettings()?.intakeEmail;
    if (!intakeEmail) {
      this.copyStatus.set('No disponible');
      return;
    }

    navigator.clipboard
      ?.writeText(intakeEmail)
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
    const lawyer =
      this.settingsForm.lawyers[this.activeLawyerIndex()] ??
      this.settingsForm.lawyers[0] ??
      this.blankLawyer();
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
    this.activeRuleIndex.set(this.settingsForm.routingRules.length - 1);
    this.clampActiveIndexes();
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
    this.clampActiveIndexes();
  }

  previousRoutingRule(): void {
    this.activeRuleIndex.update((index) => Math.max(0, index - 1));
  }

  nextRoutingRule(): void {
    this.activeRuleIndex.update((index) =>
      this.clampedIndex(index + 1, this.settingsForm.routingRules.length),
    );
  }

  isLastRoutingRule(): boolean {
    return this.activeRuleIndex() >= this.settingsForm.routingRules.length - 1;
  }

  addLawyer(): void {
    this.settingsForm.lawyers = [...this.settingsForm.lawyers, this.blankLawyer()];
    this.activeLawyerIndex.set(this.settingsForm.lawyers.length - 1);
    this.clampActiveIndexes();
  }

  removeLawyer(index: number): void {
    if (this.settingsForm.lawyers.length === 1) {
      this.settingsErrorMessage.set('Manten al menos un abogado.');
      return;
    }
    const removed = this.settingsForm.lawyers[index];
    this.settingsForm.lawyers = this.settingsForm.lawyers.filter(
      (_, itemIndex) => itemIndex !== index,
    );
    const fallback = this.settingsForm.lawyers[0];
    if (removed) {
      this.settingsForm.routingRules = this.settingsForm.routingRules.map((rule) =>
        rule.lawyerId === removed.id
          ? { ...rule, lawyerId: fallback.id, destinationEmail: fallback.email }
          : rule,
      );
    }
    this.clampActiveIndexes();
  }

  previousLawyer(): void {
    this.activeLawyerIndex.update((index) => Math.max(0, index - 1));
  }

  nextLawyer(): void {
    this.activeLawyerIndex.update((index) =>
      this.clampedIndex(index + 1, this.settingsForm.lawyers.length),
    );
  }

  isLastLawyer(): boolean {
    return this.activeLawyerIndex() >= this.settingsForm.lawyers.length - 1;
  }

  addAvailabilityWindow(lawyer: LawyerForm): void {
    lawyer.availabilityWindows = [
      ...lawyer.availabilityWindows,
      { weekday: 1, startTime: '09:00', endTime: '17:00', timezone: 'America/Bogota' },
    ];
  }

  removeAvailabilityWindow(lawyer: LawyerForm, index: number): void {
    lawyer.availabilityWindows = lawyer.availabilityWindows.filter(
      (_, itemIndex) => itemIndex !== index,
    );
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
      this.consultationsErrorMessage.set(
        'Completa nombre, email y resumen para crear la consulta.',
      );
      return;
    }

    this.isSubmitting.set(true);
    this.consultationsErrorMessage.set('');
    this.http
      .post<Consultation>(this.apiConfig.url('/api/consultations'), {
        clientName: this.form.clientName.trim(),
        clientEmail: this.form.clientEmail.trim(),
        summary: this.form.summary.trim(),
        preferredWindow: this.form.preferredWindow.trim(),
      })
      .subscribe({
        next: (consultation) => {
          this.consultations.update((items) => [consultation, ...items]);
          this.consultationsErrorMessage.set('');
          this.statusMessage.set('Consulta creada, clasificada y agendada.');
          this.form.clientName = '';
          this.form.clientEmail = '';
          this.form.summary = '';
          this.form.preferredWindow = '';
          this.isCreateOpen.set(false);
          this.selectedConsultationId.set(consultation.id);
          this.isSubmitting.set(false);
        },
        error: (error: HttpErrorResponse) =>
          this.handleProtectedError(error, () => {
            this.consultationsErrorMessage.set('No se pudo crear la consulta. Intenta nuevamente.');
            this.isSubmitting.set(false);
          }),
      });
  }

  urgencyLabel(consultation: Consultation): string {
    return consultation.event?.urgencyName ?? consultation.urgency;
  }

  isHighestUrgency(consultation: Consultation): boolean {
    const settings = this.tenantSettings();
    const matchedRule = settings?.routingRules?.find(
      (rule) => rule.name === consultation.consultationType,
    );
    const definitions =
      matchedRule?.urgencyDefinitions?.filter((definition) => definition.active) ?? [];
    if (definitions.length) {
      const highest = [...definitions].sort((a, b) => a.rank - b.rank).at(-1);
      return consultation.urgency === highest?.name;
    }
    const levels = matchedRule?.urgencyLevels?.length
      ? matchedRule.urgencyLevels
      : settings?.urgencyLevels?.length
        ? settings.urgencyLevels
        : ['NORMAL', 'URGENT'];
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

  // --- Pre-checkout onboarding ---------------------------------------------
  // Two interactive steps before the paywall: configure lawyers (max 3) and
  // routing rules, both persisted through the regular settings API.

  private startOnboarding(): void {
    this.view.set('onboard-lawyers');
    this.onboardingError.set('');
    this.settingsLoaded.set(false);
    this.demoDeliveries.set({});
    this.envelopeFlights.set([]);
    this.routeConnectors.set([]);
    this.http.get<TenantSettingsResponse>(this.apiConfig.url('/api/tenant/settings')).subscribe({
      next: (settings) => {
        this.tenantSettings.set(settings);
        this.settingsLoaded.set(true);
        const lawyers = this.lawyerFormsFrom(settings);
        this.settingsForm.lawyers = lawyers;
        // Prefill only what the firm actually saved: a fresh firm starts with an
        // empty canvas (no lawyers, no routes) so the diagram builds up live.
        this.onboardingLawyers.set(settings.lawyers?.length ? lawyers : []);
        this.onboardingRules.set(
          settings.routingRules?.length ? this.routingRuleFormsFrom(settings) : [],
        );
        this.resetLawyerDraft();
        this.resetRuleDraft();
      },
      error: () => {
        this.settingsLoaded.set(true);
        this.onboardingError.set(
          'No se pudo cargar la configuracion inicial. Refresca la pagina para reintentar.',
        );
      },
    });
  }

  addOnboardingLawyer(): void {
    if (this.onboardingLawyerLimitReached()) {
      return;
    }
    const draft = this.lawyerDraft;
    const error = this.lawyerDraftError(draft);
    if (error) {
      this.onboardingError.set(error);
      return;
    }
    this.onboardingError.set('');
    this.onboardingLawyers.update((lawyers) => [
      ...lawyers,
      {
        ...draft,
        displayName: draft.displayName.trim(),
        email: draft.email.trim().toLowerCase(),
        availabilityWindows: draft.availabilityWindows.map((window) => ({ ...window })),
      },
    ]);
    this.resetLawyerDraft();
  }

  private lawyerDraftError(draft: LawyerForm): string | null {
    if (!draft.displayName.trim()) {
      return 'Ingresa el nombre del abogado.';
    }
    if (!this.isValidEmail(draft.email.trim())) {
      return 'Ingresa un email valido para el abogado.';
    }
    const email = draft.email.trim().toLowerCase();
    if (this.onboardingLawyers().some((lawyer) => lawyer.email.toLowerCase() === email)) {
      return 'Ese email ya esta en la lista de abogados.';
    }
    if (!draft.defaultEventDurationMinutes || Number(draft.defaultEventDurationMinutes) < 15) {
      return 'La duracion debe ser de al menos 15 minutos.';
    }
    if (!draft.availabilityWindows.length) {
      return 'Agrega al menos una ventana disponible.';
    }
    if (
      draft.availabilityWindows.some(
        (window) => !window.startTime || !window.endTime || window.startTime >= window.endTime,
      )
    ) {
      return 'Cada ventana necesita hora de inicio menor a la de fin.';
    }
    return null;
  }

  removeOnboardingLawyer(index: number): void {
    const removed = this.onboardingLawyers()[index];
    this.onboardingLawyers.update((lawyers) => lawyers.filter((_, i) => i !== index));
    if (removed?.id) {
      this.onboardingRules.update((rules) => rules.filter((rule) => rule.lawyerId !== removed.id));
      this.demoDeliveries.update((map) => {
        const next = { ...map };
        delete next[removed.id as string];
        return next;
      });
      if (this.ruleDraft.lawyerId === removed.id) {
        this.resetRuleDraft();
      }
    }
    this.onboardingError.set('');
    this.scheduleConnectorRefresh();
  }

  goToOnboardingRules(): void {
    if (!this.onboardingLawyers().length) {
      this.onboardingError.set('Agrega al menos un abogado para continuar.');
      return;
    }
    this.onboardingError.set('');
    if (!this.ruleDraft.lawyerId) {
      this.resetRuleDraft();
    }
    this.view.set('onboard-rules');
    this.scheduleConnectorRefresh();
    this.startDemoLoop();
  }

  backToOnboardingLawyers(): void {
    this.stopDemoLoop();
    this.envelopeFlights.set([]);
    this.onboardingError.set('');
    this.view.set('onboard-lawyers');
  }

  saveOnboardingRule(): void {
    const draft = this.ruleDraft;
    const lawyer = this.onboardingLawyers().find((item) => item.id === draft.lawyerId);
    if (!draft.name.trim()) {
      this.onboardingError.set('Ponle un nombre a la regla.');
      return;
    }
    if (!lawyer) {
      this.onboardingError.set('Selecciona la abogada que atiende esta regla.');
      return;
    }
    if (!this.hasValidUrgencyDefinitions(this.definitionsForSave(draft))) {
      this.onboardingError.set('Configura niveles de urgencia sin vacios ni duplicados.');
      return;
    }
    this.onboardingError.set('');
    this.onboardingRules.update((rules) => [
      ...rules,
      {
        ...draft,
        name: draft.name.trim(),
        lawyerId: lawyer.id,
        destinationEmail: lawyer.email,
        urgencyDefinitions: draft.urgencyDefinitions.map((definition) => ({ ...definition })),
      },
    ]);
    this.resetRuleDraft();
    const lawyerId = lawyer.id as string;
    this.scheduleConnectorRefresh(() => this.launchEnvelope(lawyerId));
    this.startDemoLoop();
  }

  removeOnboardingRule(index: number): void {
    this.onboardingRules.update((rules) => rules.filter((_, i) => i !== index));
    this.onboardingError.set('');
    this.scheduleConnectorRefresh();
  }

  finishOnboarding(): void {
    if (!this.onboardingRules().length) {
      this.onboardingError.set('Crea al menos una regla para continuar al pago.');
      return;
    }
    const lawyers = this.lawyersPayload(this.onboardingLawyers());
    const routingRules = this.rulesPayload(this.onboardingRules(), lawyers);
    const validationError = this.settingsPayloadError(lawyers, routingRules);
    if (validationError) {
      this.onboardingError.set(validationError);
      return;
    }
    this.isSubmitting.set(true);
    this.onboardingError.set('');
    this.http
      .put<TenantSettingsResponse>(this.apiConfig.url('/api/tenant/settings'), {
        lawyers,
        routingRules,
      })
      .subscribe({
        next: (settings) => {
          this.tenantSettings.set(settings);
          this.settingsForm.lawyers = this.lawyerFormsFrom(settings);
          this.settingsForm.routingRules = this.routingRuleFormsFrom(settings);
          this.clampActiveIndexes();
          this.isSubmitting.set(false);
          this.stopDemoLoop();
          this.envelopeFlights.set([]);
          this.view.set('billing');
          this.loadBillingPlans();
        },
        error: (error: HttpErrorResponse) => {
          this.onboardingError.set(
            error.status === 409
              ? 'Ese email ya esta configurado para otra firma.'
              : 'No se pudo guardar la configuracion. Intenta nuevamente.',
          );
          this.isSubmitting.set(false);
        },
      });
  }

  private resetLawyerDraft(): void {
    this.lawyerDraft = { ...this.blankLawyer(), email: '' };
  }

  private blankRuleDraft(): TenantRoutingRuleForm {
    return {
      name: '',
      description: '',
      lawyerId: null,
      destinationEmail: '',
      urgentKeywords: '',
      consultationWindows: '',
      urgencyLevels: 'NORMAL, URGENT',
      urgencyDefinitions: [
        { name: 'NORMAL', rank: 1, slaDays: 5, active: true },
        { name: 'URGENT', rank: 2, slaDays: 1, active: true },
      ],
    };
  }

  private resetRuleDraft(): void {
    const firstLawyer = this.onboardingLawyers()[0];
    this.ruleDraft = {
      ...this.blankRuleDraft(),
      lawyerId: firstLawyer?.id ?? null,
      destinationEmail: firstLawyer?.email ?? '',
    };
  }

  lawyerNameFor(lawyerId: string | null): string {
    const lawyer = this.onboardingLawyers().find((item) => item.id === lawyerId);
    return lawyer?.displayName || lawyer?.email || 'Abogado';
  }

  // Route diagram geometry: dashed orthogonal connectors measured from the DOM
  // (inbox node → each routed lawyer card), reused as the envelope offset-path.
  private scheduleConnectorRefresh(after?: () => void): void {
    requestAnimationFrame(() => {
      this.computeRouteConnectors();
      after?.();
    });
  }

  private computeRouteConnectors(): void {
    const host = this.routeDiagram?.nativeElement;
    if (!host) {
      this.routeConnectors.set([]);
      return;
    }
    const hostBox = host.getBoundingClientRect();
    this.diagramSize.set({ width: Math.round(hostBox.width), height: Math.round(hostBox.height) });
    const inbox = host.querySelector('[data-inbox-node]');
    if (!inbox) {
      this.routeConnectors.set([]);
      return;
    }
    const inboxBox = inbox.getBoundingClientRect();
    const x0 = Math.round(inboxBox.right - hostBox.left);
    const y0 = Math.round(inboxBox.top + inboxBox.height / 2 - hostBox.top);
    const routedIds = new Set(this.onboardingRules().map((rule) => rule.lawyerId));
    const connectors: RouteConnector[] = [];
    host.querySelectorAll<HTMLElement>('[data-lawyer-node]').forEach((node) => {
      const lawyerId = node.dataset['lawyerNode'];
      if (!lawyerId || !routedIds.has(lawyerId)) {
        return;
      }
      const box = node.getBoundingClientRect();
      const x1 = Math.round(box.left - hostBox.left);
      const y1 = Math.round(box.top + box.height / 2 - hostBox.top);
      const mx = Math.round(x0 + (x1 - x0) / 2);
      connectors.push({ lawyerId, d: `M ${x0} ${y0} L ${mx} ${y0} L ${mx} ${y1} L ${x1} ${y1}` });
    });
    this.routeConnectors.set(connectors);
  }

  @HostListener('window:resize')
  onWindowResize(): void {
    if (this.view() !== 'onboard-rules') {
      return;
    }
    // Paths are measured in px, so in-flight envelopes go stale on resize.
    this.envelopeFlights.set([]);
    this.scheduleConnectorRefresh();
  }

  private launchEnvelope(lawyerId: string): void {
    const connector = this.routeConnectors().find((item) => item.lawyerId === lawyerId);
    if (!connector) {
      return;
    }
    this.envelopeFlights.update((flights) => [
      ...flights,
      { id: ++this.flightSeq, lawyerId, d: connector.d },
    ]);
  }

  envelopeArrived(flight: EnvelopeFlight): void {
    this.envelopeFlights.update((flights) => flights.filter((item) => item.id !== flight.id));
    this.demoDeliveries.update((map) => ({
      ...map,
      [flight.lawyerId]: Math.min((map[flight.lawyerId] ?? 0) + 1, 8),
    }));
  }

  offsetPathFor(flight: EnvelopeFlight): string {
    return `path('${flight.d}')`;
  }

  private startDemoLoop(): void {
    if (this.demoLoopHandle != null) {
      return;
    }
    this.demoLoopHandle = window.setInterval(() => this.sendDemoEnvelope(), 4200);
  }

  private stopDemoLoop(): void {
    if (this.demoLoopHandle == null) {
      return;
    }
    window.clearInterval(this.demoLoopHandle);
    this.demoLoopHandle = null;
  }

  private sendDemoEnvelope(): void {
    if (this.view() !== 'onboard-rules') {
      return;
    }
    const rules = this.onboardingRules();
    if (!rules.length) {
      return;
    }
    const rule = rules[this.demoRuleCursor++ % rules.length];
    if (rule.lawyerId) {
      this.launchEnvelope(rule.lawyerId);
    }
  }

  // Weekly calendar brief: availability windows (and demo consultation boxes)
  // rendered proportionally on a 07:00–20:00 day column.
  private static readonly calStartMin = 7 * 60;
  private static readonly calEndMin = 20 * 60;

  lawyerWeekdayColumns(lawyer: LawyerForm): { weekday: number; label: string }[] {
    const labels = ['', 'L', 'M', 'X', 'J', 'V', 'S', 'D'];
    const extra = [6, 7].filter((day) =>
      lawyer.availabilityWindows.some((window) => Number(window.weekday) === day),
    );
    return [1, 2, 3, 4, 5, ...extra].map((weekday) => ({ weekday, label: labels[weekday] }));
  }

  availabilityBlocks(lawyer: LawyerForm, weekday: number): CalendarBlock[] {
    return lawyer.availabilityWindows
      .filter((window) => Number(window.weekday) === weekday)
      .map((window) =>
        this.calendarBlock(
          this.minutesOf(window.startTime),
          this.minutesOf(window.endTime),
          `${window.startTime}–${window.endTime}`,
        ),
      )
      .filter((block): block is CalendarBlock => block !== null);
  }

  demoBlocks(lawyer: LawyerForm, weekday: number): CalendarBlock[] {
    const count = this.demoDeliveries()[lawyer.id ?? ''] ?? 0;
    if (!count) {
      return [];
    }
    const windows = [...lawyer.availabilityWindows].sort(
      (a, b) => Number(a.weekday) - Number(b.weekday) || a.startTime.localeCompare(b.startTime),
    );
    if (!windows.length) {
      return [];
    }
    const duration = Math.max(15, Number(lawyer.defaultEventDurationMinutes) || 60);
    const blocks: CalendarBlock[] = [];
    for (let i = 0; i < count; i++) {
      const window = windows[i % windows.length];
      if (Number(window.weekday) !== weekday) {
        continue;
      }
      const slot = Math.floor(i / windows.length);
      const start = this.minutesOf(window.startTime) + slot * duration;
      if (start + duration > this.minutesOf(window.endTime)) {
        continue;
      }
      const block = this.calendarBlock(start, start + duration, 'Consulta');
      if (block) {
        blocks.push(block);
      }
    }
    return blocks;
  }

  private calendarBlock(startMin: number, endMin: number, label: string): CalendarBlock | null {
    const lo = ConsoleComponent.calStartMin;
    const hi = ConsoleComponent.calEndMin;
    const start = Math.max(startMin, lo);
    const end = Math.min(endMin, hi);
    if (end <= start) {
      return null;
    }
    const range = hi - lo;
    return {
      topPct: ((start - lo) / range) * 100,
      heightPct: ((end - start) / range) * 100,
      label,
    };
  }

  private minutesOf(time: string): number {
    const [hours, minutes] = (time || '').split(':').map(Number);
    return (hours || 0) * 60 + (minutes || 0);
  }

  private blankLawyer(): LawyerForm {
    const id = this.cryptoId();
    return {
      id,
      displayName: '',
      email: this.sessionEmail(),
      meetingUrl: '',
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
      : [
          {
            id: this.cryptoId(),
            displayName: 'Abogado principal',
            email: settings.destinationEmail ?? this.sessionEmail(),
            meetingUrl: null,
            active: true,
            defaultEventDurationMinutes: 60,
            availabilityWindows: this.defaultAvailability(),
          },
        ];

    return lawyers.map((lawyer) => ({
      id: lawyer.id ?? this.cryptoId(),
      displayName: lawyer.displayName ?? '',
      email: lawyer.email ?? '',
      meetingUrl: lawyer.meetingUrl ?? '',
      active: lawyer.active ?? true,
      defaultEventDurationMinutes: lawyer.defaultEventDurationMinutes ?? 60,
      availabilityWindows: lawyer.availabilityWindows?.length
        ? lawyer.availabilityWindows
        : this.defaultAvailability(),
    }));
  }

  private routingRuleFormsFrom(settings: TenantSettingsResponse): TenantRoutingRuleForm[] {
    const lawyers = this.settingsForm.lawyers.length
      ? this.settingsForm.lawyers
      : this.lawyerFormsFrom(settings);
    const fallbackLawyerId = lawyers[0]?.id ?? null;
    const rules = settings.routingRules?.length
      ? settings.routingRules
      : [
          {
            name: 'Default intake route',
            description: null,
            urgentKeywords: settings.urgentKeywords ?? [],
            consultationWindows: settings.consultationWindows ?? [],
            urgencyLevels: settings.urgencyLevels?.length
              ? settings.urgencyLevels
              : ['NORMAL', 'URGENT'],
            lawyerId: fallbackLawyerId,
            urgencyDefinitions: this.urgencyDefinitionsFromLevels(settings.urgencyLevels),
            destinationEmail: settings.destinationEmail,
          },
        ];

    return rules.map((rule, index) => {
      const lawyerId =
        rule.lawyerId ?? this.lawyerIdForEmail(lawyers, rule.destinationEmail) ?? fallbackLawyerId;
      const lawyer = lawyers.find((item) => item.id === lawyerId);
      return {
        name: rule.name || `Route ${index + 1}`,
        description: rule.description ?? '',
        lawyerId,
        destinationEmail: rule.destinationEmail ?? lawyer?.email ?? this.sessionEmail(),
        urgentKeywords: (rule.urgentKeywords ?? []).join(', '),
        consultationWindows: '',
        urgencyLevels: (rule.urgencyLevels?.length
          ? rule.urgencyLevels
          : this.activeUrgencyNames(rule.urgencyDefinitions)
        ).join(', '),
        urgencyDefinitions: rule.urgencyDefinitions?.length
          ? rule.urgencyDefinitions.map((item) => ({ ...item }))
          : this.urgencyDefinitionsFromLevels(rule.urgencyLevels),
      };
    });
  }

  private definitionsForSave(rule: TenantRoutingRuleForm): UrgencyDefinition[] {
    return rule.urgencyDefinitions.map((definition, index) => ({
      ...definition,
      rank: index + 1,
      active: true,
    }));
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
    const names = definitions
      .map((definition) => definition.name.trim().toLowerCase())
      .filter(Boolean);
    return (
      Boolean(active.length) &&
      names.length === definitions.length &&
      names.length === new Set(names).size &&
      definitions.every(
        (definition) => Number(definition.rank) > 0 && Number(definition.slaDays) >= 0,
      )
    );
  }

  private clampActiveIndexes(): void {
    this.activeLawyerIndex.set(
      this.clampedIndex(this.activeLawyerIndex(), this.settingsForm.lawyers.length),
    );
    this.activeRuleIndex.set(
      this.clampedIndex(this.activeRuleIndex(), this.settingsForm.routingRules.length),
    );
  }

  private clampedIndex(index: number, length: number): number {
    if (length <= 0) {
      return 0;
    }
    return Math.min(Math.max(index, 0), length - 1);
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

  private checkoutAttempt(): BillingCheckoutAttempt | null {
    try {
      const stored = sessionStorage.getItem(ConsoleComponent.checkoutAttemptStorageKey);
      if (!stored) return null;
      const attempt = JSON.parse(stored) as Partial<BillingCheckoutAttempt>;
      if (
        typeof attempt.tenantId !== 'string' ||
        typeof attempt.planCode !== 'string' ||
        typeof attempt.couponCode !== 'string' ||
        typeof attempt.idempotencyKey !== 'string'
      ) {
        this.clearCheckoutAttempt();
        return null;
      }
      return attempt as BillingCheckoutAttempt;
    } catch {
      this.clearCheckoutAttempt();
      return null;
    }
  }

  private storeCheckoutAttempt(attempt: BillingCheckoutAttempt): void {
    sessionStorage.setItem(ConsoleComponent.checkoutAttemptStorageKey, JSON.stringify(attempt));
  }

  private clearCheckoutAttempt(): void {
    sessionStorage.removeItem(ConsoleComponent.checkoutAttemptStorageKey);
  }

  private isValidEmail(value: string): boolean {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
  }

  private clearConsoleErrors(): void {
    this.consultationsErrorMessage.set('');
    this.settingsErrorMessage.set('');
  }
}
