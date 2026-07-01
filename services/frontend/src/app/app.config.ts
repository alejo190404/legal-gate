import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { ApiConfigService } from './config/api-config.service';
import { AuthService } from './auth/auth.service';
import { authInterceptor } from './auth/auth.interceptor';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideAppInitializer(async () => {
      const apiConfigService = inject(ApiConfigService);
      const authService = inject(AuthService);
      await apiConfigService.load();
      await authService.initialize();
    }),
  ],
};
