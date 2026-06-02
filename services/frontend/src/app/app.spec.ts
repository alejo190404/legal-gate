import { TestBed } from '@angular/core/testing';
import { App } from './app';

describe('App landing page', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
    }).compileComponents();
  });

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders the Colombian Spanish LegalGate landing page hero', async () => {
    const compiled = await render();

    expect(compiled.querySelector('.brand')?.textContent).toContain('LegalGate');
    expect(compiled.querySelector('h1')?.textContent).toContain(
      'Convierte cada correo de un cliente en una consulta agendada',
    );
    expect(compiled.textContent).toContain('Diseñado para firmas en Colombia');
    expect(compiled.textContent).toContain('Agenda una demo');
  });

  it('keeps the scope to marketing landing sections only', async () => {
    const compiled = await render();
    const sectionIds = Array.from(compiled.querySelectorAll('section[id]')).map((section) => section.id);

    expect(sectionIds).toEqual(['problema', 'como-funciona', 'beneficios', 'seguridad', 'piloto']);
    expect(compiled.textContent?.toLowerCase()).not.toContain('dashboard');
    expect(compiled.textContent?.toLowerCase()).not.toContain('consola');
  });

  it('uses the LegalGate design system primitives', async () => {
    const compiled = await render();

    expect(compiled.querySelector('.pixel-card')).toBeTruthy();
    expect(compiled.querySelector('.workflow-grid')).toBeTruthy();
    expect(compiled.querySelector('.eyebrow')?.textContent).toContain('INTAKE · ROUTING · AUDIT');
  });
});
