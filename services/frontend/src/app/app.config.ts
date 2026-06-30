import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { ApiConfigService } from './config/api-config.service';
import { AuthService } from './auth/auth.service';
import { authInterceptor } from './auth/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideAppInitializer(async () => {
      const apiConfigService = inject(ApiConfigService);
      const authService = inject(AuthService);
      await apiConfigService.load();
      await authService.initialize();
    }),
  ],
};
