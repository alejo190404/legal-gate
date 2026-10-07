import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiConfigService } from '../config/api-config.service';
import { ActivityTypesComponent } from './activity-types';

describe('ActivityTypesComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ActivityTypesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), ApiConfigService],
    }).compileComponents();
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('lists the catalog, creates a type with only its own kind of default, and retires one', () => {
    const fixture = TestBed.createComponent(ActivityTypesComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/activity-types').flush([
      { id: 't1', name: 'Fallo de tutela', kind: 'EXTERNAL', defaultTurnaroundDays: null, defaultTermDays: 10, retiredAt: null },
      { id: 't2', name: 'Audiencia', kind: 'DATED', defaultTurnaroundDays: null, defaultTermDays: null, retiredAt: '2026-10-01T00:00:00Z' },
    ]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Término legal: 10 días hábiles');
    expect(fixture.nativeElement.textContent).toContain('Retirado');

    const component = fixture.componentInstance;
    component.startCreate();
    component.form!.name = 'Asesoría';
    component.form!.kind = 'EXTERNAL';
    component.form!.defaultTurnaroundDays = 5;
    component.save();
    const create = http.expectOne('/api/activity-types');
    expect(create.request.body).toEqual({ name: 'Asesoría', kind: 'EXTERNAL', defaultTurnaroundDays: null, defaultTermDays: null });
    create.flush({});
    http.expectOne('/api/activity-types').flush([]);

    component.retire({ id: 't1', name: 'Fallo de tutela', kind: 'EXTERNAL', defaultTurnaroundDays: null, defaultTermDays: 10, retiredAt: null });
    expect(http.expectOne('/api/activity-types/t1/retire').request.method).toBe('POST');
  });

  it('explains a missing turnaround', () => {
    const fixture = TestBed.createComponent(ActivityTypesComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/activity-types').flush([]);

    fixture.componentInstance.startCreate();
    fixture.componentInstance.form!.name = 'Asesoría';
    fixture.componentInstance.form!.defaultTurnaroundDays = null;
    fixture.componentInstance.save();
    http.expectOne('/api/activity-types').flush({ error: 'turnaround_days_required' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Indica el plazo en días hábiles');
  });
});
