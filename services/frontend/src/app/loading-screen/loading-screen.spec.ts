import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { LoadingScreenComponent } from './loading-screen';

describe('LoadingScreenComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('advances the message on interval ticks and stops on destroy', async () => {
    await TestBed.configureTestingModule({ imports: [LoadingScreenComponent] }).compileComponents();
    const fixture = TestBed.createComponent(LoadingScreenComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges(); // runs ngOnInit -> starts the intervals

    const firstMessage = component.message();

    vi.advanceTimersByTime(2000);
    expect(component.message()).not.toBe(firstMessage);

    const messageAfterTicks = component.message();

    fixture.destroy(); // runs ngOnDestroy -> clears the intervals
    vi.advanceTimersByTime(10000);

    expect(component.message()).toBe(messageAfterTicks);
  });
});
