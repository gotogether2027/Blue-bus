import { HttpErrorResponse } from '@angular/common/http';
import { readApiError } from '../../core/api/api-error';

export interface OperatorMemberActionError {
  title: string;
  message: string;
}

export function readOperatorMemberActionError(
  error: unknown
): OperatorMemberActionError | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  if (error.status === 400) {
    return {
      title: 'Check the member details',
      message: readApiError(error)
    };
  }
  if (error.status === 404) {
    return {
      title: 'Member change could not be applied',
      message: readApiError(error)
    };
  }
  if (error.status === 409) {
    return {
      title: 'Member change could not be applied',
      message: readApiError(error)
    };
  }
  return null;
}
