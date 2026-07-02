import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

// Auth is fully resolved before bootstrap (provideAppInitializer awaits
// authService.initialize()), so both guards are synchronous — no landing or
// console frame renders before the redirect.
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.authenticated() ? true : inject(Router).parseUrl('/');
};

export const publicGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.authenticated() ? inject(Router).parseUrl('/dashboard') : true;
};
