import { HttpErrorResponse } from '@angular/common/http';
import { readApiError } from '../../core/api/api-error';

export interface OperatorTripActionError {
  title: string;
  message: string;
}

export function readOperatorTripActionError(
  error: unknown
): OperatorTripActionError | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  if (error.status === 400) {
    return {
      title: 'Check the trip details',
      message: readApiError(error)
    };
  }
  if (error.status === 409) {
    return {
      title: 'This trip could not be changed',
      message: readApiError(error)
    };
  }
  return null;
}
