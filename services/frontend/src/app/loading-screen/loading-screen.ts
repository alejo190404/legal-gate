import {
  Component,
  OnDestroy,
  OnInit,
  computed,
  input,
  output,
  signal,
} from '@angular/core';

@Component({
  selector: 'app-loading-screen',
  templateUrl: './loading-screen.html',
  styleUrl: './loading-screen.scss',
})
export class LoadingScreenComponent implements OnInit, OnDestroy {
  /** When set, the status line is replaced by the error message and a retry button. */
  readonly error = input<string>('');
  readonly retry = output<void>();

  readonly eyebrow = 'Intake · Enrutamiento · Auditoría';
  readonly title = 'Preparando tu consola';
  readonly note = 'No cierres esta ventana · seguimos clasificando y enrutando tu intake.';

  private readonly messages = [
    'Trabajando en ello',
    'Casi listo',
    'Clasificando intake',
    'Preparando enrutamiento',
    'Verificando disponibilidad',
    'Sincronizando calendario',
  ];
  private readonly steps = [
    // 'Leyendo bandeja',
    // 'Clasificando intake',
    // 'Aplicando reglas',
    // 'Verificando disponibilidad',
    'Preparando consola',
  ];

  // 3×3 chase grid: value is the cell's clockwise ring position (-1 = empty center).
  readonly cells = [0, 1, 2, 7, -1, 3, 6, 5, 4];

  private readonly msgIndex = signal(0);
  private readonly stepIndex = signal(0);
  readonly message = computed(() => this.messages[this.msgIndex() % this.messages.length]);
  readonly stepLabel = computed(() => this.steps[this.stepIndex() % this.steps.length]);

  private timers: ReturnType<typeof setInterval>[] = [];

  ngOnInit(): void {
    this.timers.push(setInterval(() => this.msgIndex.update((i) => i + 1), 2000));
    this.timers.push(setInterval(() => this.stepIndex.update((i) => i + 1), 2500));
  }

  ngOnDestroy(): void {
    this.timers.forEach((timer) => clearInterval(timer));
    this.timers = [];
  }
}
