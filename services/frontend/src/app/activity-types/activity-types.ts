import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiConfigService } from '../config/api-config.service';

export type ActivityKind = 'TURNAROUND' | 'DATED' | 'EXTERNAL';

export interface ActivityType {
  id: string;
  name: string;
  kind: ActivityKind;
  defaultTurnaroundDays: number | null;
  defaultTermDays: number | null;
  retiredAt: string | null;
}

interface ActivityTypeForm {
  id: string | null;
  name: string;
  kind: ActivityKind;
  defaultTurnaroundDays: number | null;
  defaultTermDays: number | null;
}

const KIND_LABELS: Record<ActivityKind, string> = {
  TURNAROUND: 'Con plazo de la firma',
  DATED: 'Con fecha (reunión, audiencia)',
  EXTERNAL: 'Externa (depende de un juez o entidad)',
};

const ERRORS: Record<string, string> = {
  activity_type_name_required: 'El nombre es obligatorio.',
  turnaround_days_required: 'Indica el plazo en días hábiles (1 a 365).',
  invalid_term_days: 'El término legal debe estar entre 1 y 365 días hábiles.',
  invalid_activity_kind: 'Escoge un tipo.',
};

@Component({
  selector: 'app-activity-types',
  imports: [FormsModule],
  templateUrl: './activity-types.html',
  styleUrls: ['../console/console.scss'],
})
export class ActivityTypesComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly apiConfig = inject(ApiConfigService);

  readonly kinds = Object.entries(KIND_LABELS).map(([kind, label]) => ({ kind: kind as ActivityKind, label }));
  readonly types = signal<ActivityType[]>([]);
  readonly errorMessage = signal('');
  readonly isSubmitting = signal(false);
  form: ActivityTypeForm | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.http.get<ActivityType[]>(this.apiConfig.url('/api/activity-types')).subscribe({
      next: (types) => this.types.set(types),
      error: () => this.errorMessage.set('No se pudo cargar el catálogo.'),
    });
  }

  kindLabel(kind: ActivityKind): string {
    return KIND_LABELS[kind];
  }

  defaultLabel(type: ActivityType): string {
    if (type.kind === 'TURNAROUND') {
      return `${type.defaultTurnaroundDays} días hábiles`;
    }
    if (type.kind === 'EXTERNAL') {
      return type.defaultTermDays ? `Término legal: ${type.defaultTermDays} días hábiles` : 'Sin término fijo';
    }
    return 'Fecha fija';
  }

  startCreate(): void {
    this.errorMessage.set('');
    this.form = { id: null, name: '', kind: 'TURNAROUND', defaultTurnaroundDays: 5, defaultTermDays: null };
  }

  startEdit(type: ActivityType): void {
    this.errorMessage.set('');
    this.form = {
      id: type.id,
      name: type.name,
      kind: type.kind,
      defaultTurnaroundDays: type.defaultTurnaroundDays,
      defaultTermDays: type.defaultTermDays,
    };
  }

  cancel(): void {
    this.form = null;
  }

  save(): void {
    const form = this.form;
    if (!form) {
      return;
    }
    const payload = {
      name: form.name,
      kind: form.kind,
      defaultTurnaroundDays: form.kind === 'TURNAROUND' ? form.defaultTurnaroundDays : null,
      defaultTermDays: form.kind === 'EXTERNAL' ? form.defaultTermDays || null : null,
    };
    const request = form.id
      ? this.http.put<ActivityType>(this.apiConfig.url(`/api/activity-types/${form.id}`), payload)
      : this.http.post<ActivityType>(this.apiConfig.url('/api/activity-types'), payload);
    this.isSubmitting.set(true);
    request.subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.form = null;
        this.load();
      },
      error: (error: HttpErrorResponse) => {
        this.isSubmitting.set(false);
        this.errorMessage.set(ERRORS[error.error?.error] ?? 'No se pudo guardar el tipo de actividad.');
      },
    });
  }

  retire(type: ActivityType): void {
    this.setRetired(type, 'retire', 'No se pudo retirar el tipo de actividad.');
  }

  restore(type: ActivityType): void {
    this.setRetired(type, 'restore', 'No se pudo restaurar el tipo de actividad.');
  }

  /** A stale form message would otherwise outlive the action that replaced it. */
  private setRetired(type: ActivityType, action: 'retire' | 'restore', failure: string): void {
    this.errorMessage.set('');
    if (this.form?.id === type.id) {
      this.form = null;
    }
    this.http.post<ActivityType>(this.apiConfig.url(`/api/activity-types/${type.id}/${action}`), {}).subscribe({
      next: () => this.load(),
      error: () => this.errorMessage.set(failure),
    });
  }
}
