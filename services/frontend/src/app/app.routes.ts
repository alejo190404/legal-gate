import { Routes } from '@angular/router';
import { authGuard, publicGuard } from './auth/auth.guard';
import { LandingComponent } from './landing/landing';

export const routes: Routes = [
  { path: '', component: LandingComponent, canActivate: [publicGuard] },
  // Loaded on demand so landing-page visitors don't download the firm console.
  {
    path: 'dashboard',
    loadComponent: () => import('./console/console').then((m) => m.ConsoleComponent),
    canActivate: [authGuard],
  },
  { path: '**', redirectTo: '' },
];
