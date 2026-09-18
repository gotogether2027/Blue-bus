import { HttpErrorResponse } from '@angular/common/http';
import { readApiError } from '../../core/api/api-error';

export interface OperatorRouteActionError {
  title: string;
  message: string;
}

export function readOperatorRouteActionError(
  error: unknown
): OperatorRouteActionError | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  if (error.status === 400) {
    return {
      title: 'Check the route details',
      message: readApiError(error)
    };
  }
  if (error.status === 409) {
    return {
      title: 'Route change could not be applied',
      message: readApiError(error)
    };
  }
  return null;
}
