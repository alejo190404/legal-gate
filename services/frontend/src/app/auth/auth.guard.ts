import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

// A reminder email links to /dashboard?client=…; the link must survive the trip through sign-in.
const RETURN_URL = 'legalgate.returnUrl';

// Auth is fully resolved before bootstrap (provideAppInitializer awaits
// authService.initialize()), so both guards are synchronous — no landing or
// console frame renders before the redirect.
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  if (auth.authenticated()) {
    return true;
  }
  try {
    sessionStorage.setItem(RETURN_URL, state.url);
  } catch {
    // Storage blocked: the link opens the plain dashboard instead.
  }
  return inject(Router).parseUrl('/');
};

export const publicGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  if (!auth.authenticated()) {
    return true;
  }
  let target = '/dashboard';
  try {
    target = sessionStorage.getItem(RETURN_URL) ?? target;
    sessionStorage.removeItem(RETURN_URL);
  } catch {
    // Storage blocked: fall back to the plain dashboard.
  }
  return inject(Router).parseUrl(target.startsWith('/dashboard') ? target : '/dashboard');
};
