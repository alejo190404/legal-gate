import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { LandingComponent } from './landing';
import { AuthService } from '../auth/auth.service';

describe('LandingComponent', () => {
  const auth = {
    signIn: vi.fn().mockResolvedValue(undefined),
    signUp: vi.fn().mockResolvedValue(undefined),
  };

  beforeEach(async () => {
    vi.clearAllMocks();
    await TestBed.configureTestingModule({
      imports: [LandingComponent],
      providers: [{ provide: AuthService, useValue: auth }],
    }).compileComponents();
  });

  it('keeps the landing public and contains no local password form', () => {
    const fixture = TestBed.createComponent(LandingComponent);
    fixture.detectChanges();
    const html = fixture.nativeElement as HTMLElement;

    expect(html.querySelector('.landing-shell')).toBeTruthy();
    expect(html.querySelector('input[type="password"]')).toBeFalsy();
  });

  it('uses AuthKit redirects for sign-in and sign-up', () => {
    const fixture = TestBed.createComponent(LandingComponent);
    fixture.componentInstance.showLogin();
    fixture.componentInstance.showRegister();

    expect(auth.signIn).toHaveBeenCalledOnce();
    expect(auth.signUp).toHaveBeenCalledOnce();
  });
});
