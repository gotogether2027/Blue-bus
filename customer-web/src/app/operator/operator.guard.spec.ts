import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, firstValueFrom, of, throwError } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { operatorMembershipFixture } from '../../testing/operator-fixtures';
import { operatorAdminGuard, operatorMembershipGuard } from './operator.guard';
import { OperatorContextService } from './services/operator-context.service';

describe('operatorMembershipGuard', () => {
  const membership = operatorMembershipFixture();
  let memberships$: Observable<ReturnType<typeof operatorMembershipFixture>[]>;
  let selectOperator: jasmine.Spy<(operatorId: string) => boolean>;
  let membershipFor: jasmine.Spy<
    (operatorId: string) => ReturnType<typeof operatorMembershipFixture> | null
  >;
  let clearSession: jasmine.Spy<() => void>;
  const selectedOperatorId = signal<string | null>(null);

  beforeEach(() => {
    memberships$ = of([membership]);
    selectedOperatorId.set(null);
    selectOperator = jasmine.createSpy('selectOperator').and.callFake((operatorId: string) => {
      selectedOperatorId.set(operatorId);
      return true;
    });
    membershipFor = jasmine.createSpy('membershipFor').and.returnValue(membership);
    clearSession = jasmine.createSpy('clearSession');
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: OperatorContextService,
          useValue: {
            loadMemberships: () => memberships$,
            selectedOperatorId: selectedOperatorId.asReadonly(),
            selectOperator,
            membershipFor
          }
        },
        {
          provide: AuthService,
          useValue: {
            clearSession,
            accessToken: () => null,
            hasRefreshToken: () => false
          }
        }
      ]
    });
  });

  it('allows a route only when the membership endpoint returns that operator', async () => {
    const result = await runGuard('operator-1', '/operator/operator-1/buses');

    expect(result).toBeTrue();
    expect(selectOperator).toHaveBeenCalledOnceWith('operator-1');
  });

  it('redirects cross-operator navigation to the operator selector', async () => {
    const result = await runGuard('operator-2', '/operator/operator-2');
    const router = TestBed.inject(Router);

    expect(result).toEqual(
      router.createUrlTree(['/operator'], { queryParams: { accessDenied: 'true' } })
    );
  });

  it('drops a reused child route when switching to another valid operator', async () => {
    selectedOperatorId.set('operator-1');
    memberships$ = of([
      membership,
      operatorMembershipFixture({
        operatorId: 'operator-2',
        operatorDisplayName: 'Inland Express'
      })
    ]);

    const result = await runGuard('operator-2', '/operator/operator-2/trips');
    const router = TestBed.inject(Router);

    expect(result).toEqual(router.createUrlTree(['/operator', 'operator-2']));
    expect(selectedOperatorId()).toBe('operator-2');
  });

  it('clears an unauthorized session and redirects to login', async () => {
    memberships$ = throwError(
      () => new HttpErrorResponse({ status: 401, statusText: 'Unauthorized' })
    );
    const result = await runGuard('operator-1', '/operator/operator-1');
    const router = TestBed.inject(Router);

    expect(clearSession).toHaveBeenCalled();
    expect(result).toEqual(
      router.createUrlTree(['/login'], {
        queryParams: { returnUrl: '/operator/operator-1' }
      })
    );
  });

  it('allows operator admins into bus mutation routes', () => {
    expect(runAdminGuard('operator-1')).toBeTrue();
  });

  it('redirects operator staff away from bus mutation routes', () => {
    membershipFor.and.returnValue(operatorMembershipFixture({ role: 'OPERATOR_STAFF' }));
    const router = TestBed.inject(Router);

    expect(runAdminGuard('operator-1')).toEqual(
      router.createUrlTree(['/operator', 'operator-1', 'buses'], {
        queryParams: { writeAccessDenied: 'true' }
      })
    );
  });

  it('redirects operator staff away from route mutation routes', () => {
    membershipFor.and.returnValue(operatorMembershipFixture({ role: 'OPERATOR_STAFF' }));
    const router = TestBed.inject(Router);

    expect(runAdminGuard('operator-1', '/operator/operator-1/routes/new')).toEqual(
      router.createUrlTree(['/operator', 'operator-1', 'routes'], {
        queryParams: { writeAccessDenied: 'true' }
      })
    );
  });

  it('redirects operator staff away from trip mutation routes', () => {
    membershipFor.and.returnValue(operatorMembershipFixture({ role: 'OPERATOR_STAFF' }));
    const router = TestBed.inject(Router);

    expect(runAdminGuard('operator-1', '/operator/operator-1/trips/new')).toEqual(
      router.createUrlTree(['/operator', 'operator-1', 'trips'], {
        queryParams: { writeAccessDenied: 'true' }
      })
    );
  });

  async function runGuard(operatorId: string, url: string): Promise<boolean | UrlTree> {
    const result = TestBed.runInInjectionContext(() =>
      operatorMembershipGuard(
        { paramMap: convertToParamMap({ operatorId }) } as never,
        { url } as never
      )
    );
    return firstValueFrom(result as Observable<boolean | UrlTree>);
  }

  function runAdminGuard(
    operatorId: string,
    url = `/operator/${operatorId}/buses/new`
  ): boolean | UrlTree {
    return TestBed.runInInjectionContext(() =>
      operatorAdminGuard(
        {
          paramMap: convertToParamMap({}),
          parent: { paramMap: convertToParamMap({ operatorId }), parent: null }
        } as never,
        { url } as never
      )
    ) as boolean | UrlTree;
  }
});
