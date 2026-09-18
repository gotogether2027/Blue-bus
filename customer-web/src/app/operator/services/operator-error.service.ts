import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { readApiError } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';

export interface OperatorPageError {
  kind: 'unauthorized' | 'forbidden' | 'not-found' | 'conflict' | 'network' | 'server' | 'request';
  title: string;
  message: string;
}

export function canRetryOperatorLoad(error: OperatorPageError | null | undefined): boolean {
  return (
    !!error &&
    (error.kind === 'network' ||
      error.kind === 'server' ||
      error.kind === 'request' ||
      error.kind === 'not-found')
  );
}

@Injectable({ providedIn: 'root' })
export class OperatorErrorService {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  handle(error: unknown, returnUrl = this.router.url): OperatorPageError {
    if (error instanceof HttpErrorResponse) {
      if (error.status === 401) {
        this.auth.clearSession();
        if (!this.router.url.startsWith('/login')) {
          void this.router.navigate(['/login'], { queryParams: { returnUrl } });
        }
        return {
          kind: 'unauthorized',
          title: 'Sign in required',
          message: 'Your session has expired. Please sign in again.'
        };
      }
      if (error.status === 403) {
        return {
          kind: 'forbidden',
          title: 'Operator access denied',
          message: "You don't have access to this operator."
        };
      }
      if (error.status === 404) {
        return {
          kind: 'not-found',
          title: 'Resource not found',
          message: 'The requested operator resource was not found.'
        };
      }
      if (error.status === 409) {
        return {
          kind: 'conflict',
          title: 'Operator data is out of date',
          message: readApiError(error)
        };
      }
      if (error.status === 0) {
        return {
          kind: 'network',
          title: 'Could not reach BLUE BUS',
          message: readApiError(error)
        };
      }
      if (error.status >= 500) {
        return {
          kind: 'server',
          title: 'Operator data is unavailable',
          message: 'BLUE BUS could not load this operator data. Please try again.'
        };
      }
    }

    return {
      kind: 'request',
      title: 'Could not load operator data',
      message: readApiError(error)
    };
  }
}
