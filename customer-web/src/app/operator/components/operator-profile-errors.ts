import { HttpErrorResponse } from '@angular/common/http';
import { readApiError } from '../../core/api/api-error';

export interface OperatorProfileActionError {
  title: string;
  message: string;
}

export function readOperatorProfileActionError(
  error: unknown
): OperatorProfileActionError | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  if (error.status === 400) {
    return {
      title: 'Check the support contact details',
      message: readApiError(error)
    };
  }
  if (error.status === 409) {
    return {
      title: 'Support contact could not be updated',
      message: readApiError(error)
    };
  }
  return null;
}
