import { HttpErrorResponse } from '@angular/common/http';
import { readApiError } from '../../core/api/api-error';

export interface OperatorInventoryActionError {
  title: string;
  message: string;
}

export function readOperatorInventoryActionError(
  error: unknown
): OperatorInventoryActionError | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  if (error.status === 400) {
    return {
      title: 'Check the seat details',
      message: readApiError(error)
    };
  }
  if (error.status === 409) {
    return {
      title: 'This seat could not be changed',
      message: readApiError(error)
    };
  }
  return null;
}
