import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { ApiConfigService } from '../config/api-config.service';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const config = inject(ApiConfigService);
  if (!config.isGatewayUrl(request.url)) {
    return next(request);
  }

  const send = (forceRefresh: boolean) =>
    from(auth.getAccessToken(forceRefresh)).pipe(
      switchMap((token) =>
        next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })),
      ),
    );

  return send(false).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        return send(true);
      }
      return throwError(() => error);
    }),
  );
};
