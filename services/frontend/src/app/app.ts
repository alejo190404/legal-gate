import { Component } from '@angular/core';

interface WorkflowStep {
  icon: string;
  number: string;
  title: string;
  description: string;
}

interface Benefit {
  icon: string;
  title: string;
  description: string;
}

interface Metric {
  value: string;
  label: string;
}

@Component({
  selector: 'app-root',
  imports: [],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  readonly workflowSteps: WorkflowStep[] = [
    {
      icon: 'mail',
      number: '01',
      title: 'Recibe',
      description:
        'Conecta el correo de intake de tu firma. Cada solicitud entra a LegalGate en segundos.',
    },
    {
      icon: 'tag',
      number: '02',
      title: 'Clasifica',
      description:
        'Identifica área de práctica, urgencia, ciudad y señales de conflicto con trazabilidad.',
    },
    {
      icon: 'route',
      number: '03',
      title: 'Enruta',
      description:
        'Aplica reglas de disponibilidad, especialidad y carga de trabajo para asignar el caso.',
    },
    {
      icon: 'check-double',
      number: '04',
      title: 'Confirma',
      description:
        'Propone horario, agenda la consulta y envía confirmación al cliente con registro auditable.',
    },
  ];

  readonly benefits: Benefit[] = [
    {
      icon: 'sliders',
      title: 'Reglas por firma',
      description:
        'Cada firma configura sus propias reglas de intake, prioridad, jurisdicción y asignación.',
    },
    {
      icon: 'calendar',
      title: 'Agenda real',
      description:
        'Sincroniza disponibilidad para evitar dobles reservas y confirmar citas viables.',
    },
    {
      icon: 'file',
      title: 'Rastro auditable',
      description:
        'Cada clasificación, cambio de estado y confirmación queda registrado para revisión.',
    },
    {
      icon: 'gavel',
      title: 'Lenguaje legal',
      description:
        'Trabaja con materia, área de práctica, conflicto, consulta, SLA y jurisdicción.',
    },
    {
      icon: 'mail-open',
      title: 'Plantillas de correo',
      description:
        'Respuestas consistentes para confirmaciones, recordatorios y solicitudes de información.',
    },
    {
      icon: 'building',
      title: 'Multi-tenant desde el inicio',
      description:
        'Datos, reglas, buzones y equipos aislados para operar varias firmas con seguridad.',
    },
  ];

  readonly metrics: Metric[] = [
    { value: '41 s', label: 'De correo recibido a ruta sugerida' },
    { value: '92%', label: 'De solicitudes clasificadas sin intervención manual' },
    { value: '4 pasos', label: 'Para pasar de correo a consulta confirmada' },
  ];
}
