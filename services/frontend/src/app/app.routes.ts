import { Routes } from '@angular/router';
import { authGuard, publicGuard } from './auth/auth.guard';
import { ConsoleComponent } from './console/console';
import { LandingComponent } from './landing/landing';

export const routes: Routes = [
  { path: '', component: LandingComponent, canActivate: [publicGuard] },
  { path: 'dashboard', component: ConsoleComponent, canActivate: [authGuard] },
  { path: '**', redirectTo: '' },
];
