import { HttpErrorResponse } from '@angular/common/http';
import { readApiError } from './api-error';

describe('readApiError', () => {
  it('returns field violations and public API messages', () => {
    expect(
      readApiError(
        new HttpErrorResponse({
          status: 400,
          statusText: 'Bad Request',
          error: {
            fieldViolations: [{ field: 'supportEmail', message: 'must be a well-formed email address' }]
          }
        })
      )
    ).toBe('must be a well-formed email address');
    expect(
      readApiError(
        new HttpErrorResponse({
          status: 409,
          statusText: 'Conflict',
          error: { message: 'The last OPERATOR_ADMIN cannot be deactivated.' }
        })
      )
    ).toBe('The last OPERATOR_ADMIN cannot be deactivated.');
  });

  it('does not expose backend stack traces', () => {
    expect(
      readApiError(
        new HttpErrorResponse({
          status: 500,
          statusText: 'Server Error',
          error: {
            message: 'NullPointerException: cannot invoke\n\tat in.bluebustickets.Service.fail(Service.java:12)'
          }
        })
      )
    ).toBe('Something went wrong. Please try again.');
  });
});
