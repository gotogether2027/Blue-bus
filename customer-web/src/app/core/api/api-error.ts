import { HttpErrorResponse } from '@angular/common/http';
import { ApiErrorBody } from './models';

export function readApiError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'Unable to reach BLUE BUS. Check your connection and try again.';
    }
    const body = error.error as ApiErrorBody | string | null;
    if (body && typeof body === 'object') {
      const violations = body.fieldViolations
        ?.map((item) => item.message)
        .filter((message): message is string => !!message);
      if (violations && violations.length > 0) {
        return violations.join(' ');
      }
      if (body.message) {
        return body.message;
      }
      if (body.error) {
        return body.error;
      }
    }
    if (error.status === 401) {
      return 'Please sign in to continue.';
    }
    if (error.status === 403) {
      return 'You do not have access to that resource.';
    }
    if (error.status === 404) {
      return 'The requested record was not found.';
    }
    return error.statusText || 'Request failed.';
  }
  return 'Something went wrong. Please try again.';
}
