import { HttpErrorResponse } from '@angular/common/http';
import { readApiError } from '../../core/api/api-error';

export interface OperatorBusActionError {
  title: string;
  message: string;
}

export function readOperatorBusActionError(
  error: unknown
): OperatorBusActionError | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  if (error.status === 400) {
    return {
      title: 'Check the bus details',
      message: readApiError(error)
    };
  }
  if (error.status === 409) {
    return {
      title: 'Bus change could not be applied',
      message: readApiError(error)
    };
  }
  return null;
}
