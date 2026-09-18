import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { OperatorErrorService, canRetryOperatorLoad } from './operator-error.service';

describe('OperatorErrorService', () => {
  let service: OperatorErrorService;
  let clearSession: jasmine.Spy<() => void>;
  let navigate: jasmine.Spy;

  beforeEach(async () => {
    clearSession = jasmine.createSpy('clearSession');
    await TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'login', children: [] }]),
        {
          provide: AuthService,
          useValue: {
            clearSession,
            accessToken: () => null,
            hasRefreshToken: () => false
          }
        }
      ]
    }).compileComponents();
    service = TestBed.inject(OperatorErrorService);
    const router = TestBed.inject(Router);
    navigate = spyOn(router, 'navigate').and.resolveTo(true);
  });

  it('maps 401 to a session error and redirects to login', () => {
    const mapped = service.handle(
      new HttpErrorResponse({ status: 401, statusText: 'Unauthorized' }),
      '/operator/operator-1'
    );

    expect(mapped.kind).toBe('unauthorized');
    expect(mapped.message).toContain('session has expired');
    expect(clearSession).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledOnceWith(['/login'], {
      queryParams: { returnUrl: '/operator/operator-1' }
    });
  });

  it('does not issue a second login redirect when already on login', () => {
    spyOnProperty(TestBed.inject(Router), 'url', 'get').and.returnValue('/login');

    service.handle(new HttpErrorResponse({ status: 401, statusText: 'Unauthorized' }));

    expect(clearSession).toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('maps 403, 404, and 409 without clearing the session', () => {
    expect(
      service.handle(new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })).kind
    ).toBe('forbidden');
    expect(
      service.handle(new HttpErrorResponse({ status: 404, statusText: 'Not Found' })).kind
    ).toBe('not-found');
    expect(
      service.handle(
        new HttpErrorResponse({
          status: 409,
          statusText: 'Conflict',
          error: { message: 'Trip is no longer mutable.' }
        })
      )
    ).toEqual(
      jasmine.objectContaining({
        kind: 'conflict',
        message: 'Trip is no longer mutable.'
      })
    );
    expect(clearSession).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('allows retry only for transient GET failures', () => {
    expect(
      canRetryOperatorLoad({ kind: 'network', title: 'n', message: 'n' })
    ).toBeTrue();
    expect(
      canRetryOperatorLoad({ kind: 'server', title: 's', message: 's' })
    ).toBeTrue();
    expect(
      canRetryOperatorLoad({ kind: 'request', title: 'r', message: 'r' })
    ).toBeTrue();
    expect(
      canRetryOperatorLoad({ kind: 'not-found', title: 'm', message: 'm' })
    ).toBeTrue();
    expect(
      canRetryOperatorLoad({ kind: 'unauthorized', title: 'u', message: 'u' })
    ).toBeFalse();
    expect(
      canRetryOperatorLoad({ kind: 'forbidden', title: 'f', message: 'f' })
    ).toBeFalse();
    expect(
      canRetryOperatorLoad({ kind: 'conflict', title: 'c', message: 'c' })
    ).toBeFalse();
  });
});
