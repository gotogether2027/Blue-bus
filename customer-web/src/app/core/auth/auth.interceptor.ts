import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

const AUTH_PUBLIC = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/logout'];

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const isPublicAuth = AUTH_PUBLIC.some((path) => req.url.includes(path));
  const token = auth.accessToken();
  const outgoing =
    !isPublicAuth && token
      ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : req;

  return next(outgoing).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401 || isPublicAuth) {
        return throwError(() => error);
      }
      if (!auth.hasRefreshToken()) {
        auth.clearSession();
        return throwError(() => error);
      }
      return auth.refreshSession().pipe(
        switchMap((accessToken) =>
          next(req.clone({ setHeaders: { Authorization: `Bearer ${accessToken}` } }))
        ),
        catchError((refreshError) => {
          if (!router.url.startsWith('/login')) {
            void router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
          }
          return throwError(() => refreshError);
        })
      );
    })
  );
};
