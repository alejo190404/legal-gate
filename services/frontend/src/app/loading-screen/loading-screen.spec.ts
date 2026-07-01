import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { LoadingScreenComponent } from './loading-screen';

describe('LoadingScreenComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('advances the message and step on interval ticks and clears both on destroy', async () => {
    await TestBed.configureTestingModule({ imports: [LoadingScreenComponent] }).compileComponents();
    const fixture = TestBed.createComponent(LoadingScreenComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges(); // runs ngOnInit -> starts the intervals

    const firstMessage = component.message();
    const firstStep = component.stepLabel();

    vi.advanceTimersByTime(2000);
    expect(component.message()).not.toBe(firstMessage);

    vi.advanceTimersByTime(2500);
    expect(component.stepLabel()).not.toBe(firstStep);

    const messageAfterTicks = component.message();
    const stepAfterTicks = component.stepLabel();

    fixture.destroy(); // runs ngOnDestroy -> clears the intervals
    vi.advanceTimersByTime(10000);

    expect(component.message()).toBe(messageAfterTicks);
    expect(component.stepLabel()).toBe(stepAfterTicks);
  });
});
