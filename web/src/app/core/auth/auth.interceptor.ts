import { HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';

import { AuthStore } from './auth.store';

/**
 * Adds the bearer token to outbound requests, and on a 401 tries to refresh
 * once before giving up. If refresh fails, the user is signed out.
 *
 * /auth/* endpoints are exempt — they either need no token (register/login/refresh)
 * or shouldn't auto-retry on 401 (the response IS the signal).
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const store = inject(AuthStore);

  if (isAuthEndpoint(req.url)) {
    return next(req);
  }

  const withToken = attachToken(req, store.accessToken());

  return next(withToken).pipe(
    catchError((err) => {
      if (err?.status !== 401) {
        return throwError(() => err);
      }
      const refresh$ = store.refresh();
      if (!refresh$) {
        store.logout();
        return throwError(() => err);
      }
      return refresh$.pipe(
        switchMap(() => next(attachToken(req, store.accessToken()))),
        catchError((refreshErr) => {
          store.logout();
          return throwError(() => refreshErr);
        }),
      );
    }),
  );
};

function attachToken<T>(req: HttpRequest<T>, token: string | null): HttpRequest<T> {
  if (!token) return req;
  return req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

function isAuthEndpoint(url: string): boolean {
  return url.startsWith('/api/v1/auth/') || url.startsWith('/api/v1/health');
}
