import { AfterViewInit, Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { AuthService } from '../auth/auth.service';
import { PixelIconComponent } from '../console/pixel-icon';

interface FlowStep {
  icon: string;
  title: string;
  meta: string;
  badge: string;
  tone: string;
}

interface Stat {
  target: number;
  decimals: number;
  suffix: string;
  label: string;
}

const STEP_MS = 1300;
const COUNT_MS = 900;

@Component({
  selector: 'app-landing',
  templateUrl: './landing.html',
  imports: [PixelIconComponent],
})
export class LandingComponent implements AfterViewInit {
  private readonly auth = inject(AuthService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly destroyRef = inject(DestroyRef);
  private readonly reducedMotion =
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  readonly flow: FlowStep[] = [
    {
      icon: 'mail',
      title: 'Correo recibido',
      meta: 'cliente@correo.com · Derecho laboral',
      badge: 'Nuevo',
      tone: '',
    },
    {
      icon: 'tag',
      title: 'Clasificado',
      meta: 'Despido sin justa causa · 94%',
      badge: 'Auto',
      tone: 'is-blue',
    },
    {
      icon: 'route',
      title: 'Enrutado',
      meta: 'Equipo laboral · Bogotá · regla #3',
      badge: 'Auto',
      tone: 'is-amber',
    },
    {
      icon: 'check-double',
      title: 'Consulta confirmada',
      meta: 'Jueves 2:00 p. m. · registrado',
      badge: 'Listo',
      tone: 'is-green',
    },
  ];

  readonly stats: Stat[] = [
    {
      target: 11,
      decimals: 0,
      suffix: ' s',
      label: 'Mediana de correo entrante a consulta confirmada',
    },
    {
      target: 99,
      decimals: 0,
      suffix: ' %',
      label: 'Cantidad del proceso que se vuelve automático',
    },
    {
      target: 3.4,
      decimals: 1,
      suffix: '×',
      label: 'Más consultas agendadas por hora de intake',
    },
  ];

  readonly step = signal(0);
  readonly statValues = signal(this.stats.map(() => '0'));

  private counted = false;

  constructor() {
    if (this.reducedMotion) {
      this.step.set(this.flow.length);
      return;
    }
    const id = setInterval(
      () => this.step.update((s) => (s + 1) % (this.flow.length + 1)),
      STEP_MS,
    );
    this.destroyRef.onDestroy(() => clearInterval(id));
  }

  ngAfterViewInit(): void {
    const targets = Array.from(
      this.host.nativeElement.querySelectorAll<HTMLElement>('[data-reveal]'),
    );
    if (typeof IntersectionObserver === 'undefined') {
      this.setFinalStats();
      return;
    }
    // Hidden state is only applied once JS runs, so nothing stays invisible without it.
    for (const el of targets) el.classList.add('will-reveal');
    const io = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          entry.target.classList.add('in');
          io.unobserve(entry.target);
          if ((entry.target as HTMLElement).dataset['count'] !== undefined) {
            this.runCounters();
          }
        }
      },
      { rootMargin: '0px 0px -10% 0px' },
    );
    for (const el of targets) io.observe(el);
    this.destroyRef.onDestroy(() => io.disconnect());
  }

  showRegister(): void {
    void this.auth.signUp().catch(() => undefined);
  }

  showLogin(): void {
    void this.auth.signIn().catch(() => undefined);
  }

  private runCounters(): void {
    if (this.counted) {
      return;
    }
    this.counted = true;
    if (this.reducedMotion) {
      this.setFinalStats();
      return;
    }
    const start = performance.now();
    const tick = (): void => {
      // rAF timestamps can use a different clock origin; stick to performance.now().
      const progress = Math.min(1, Math.max(0, (performance.now() - start) / COUNT_MS));
      const eased = 1 - Math.pow(1 - progress, 3);
      this.statValues.set(this.stats.map((s) => (s.target * eased).toFixed(s.decimals)));
      if (progress < 1) {
        requestAnimationFrame(tick);
      }
    };
    requestAnimationFrame(tick);
  }

  private setFinalStats(): void {
    this.statValues.set(this.stats.map((s) => s.target.toFixed(s.decimals)));
  }
}
