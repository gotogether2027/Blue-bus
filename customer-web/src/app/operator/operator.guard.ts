import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { OperatorContextService } from './services/operator-context.service';

/**
 * Membership checks here improve navigation UX only. Every operator API call is
 * still authorized by the backend against its current membership records.
 */
export const operatorMembershipGuard: CanActivateFn = (route, state) => {
  const context = inject(OperatorContextService);
  const auth = inject(AuthService);
  const router = inject(Router);
  const operatorId = route.paramMap.get('operatorId');

  if (!operatorId) {
    return router.createUrlTree(['/operator']);
  }

  const previouslySelectedOperatorId = context.selectedOperatorId();
  return context.loadMemberships(true).pipe(
    map((memberships) => {
      const isMember = memberships.some(
        (membership) => membership.operatorId === operatorId
      );
      if (isMember) {
        context.selectOperator(operatorId);
        if (
          previouslySelectedOperatorId &&
          previouslySelectedOperatorId !== operatorId &&
          !isOperatorRootUrl(state.url, operatorId)
        ) {
          return router.createUrlTree(['/operator', operatorId]);
        }
        return true;
      }
      return router.createUrlTree(['/operator'], {
        queryParams: { accessDenied: 'true' }
      });
    }),
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        auth.clearSession();
        return of(
          router.createUrlTree(['/login'], {
            queryParams: { returnUrl: state.url }
          })
        );
      }
      return of(
        router.createUrlTree(['/operator'], {
          queryParams: { membershipsUnavailable: 'true' }
        })
      );
    })
  );
};

/**
 * Hides operator mutations from read-only members after the parent membership
 * guard has refreshed context. The backend remains authoritative for every write.
 */
export const operatorAdminGuard: CanActivateFn = (route, state) => {
  const context = inject(OperatorContextService);
  const router = inject(Router);
  const operatorId = findRouteParam(route, 'operatorId');

  if (!operatorId) {
    return router.createUrlTree(['/operator']);
  }
  if (context.membershipFor(operatorId)?.role === 'OPERATOR_ADMIN') {
    return true;
  }
  const resource = state.url.includes('/routes') ? 'routes' : 'buses';
  return router.createUrlTree(['/operator', operatorId, resource], {
    queryParams: { writeAccessDenied: 'true' }
  });
};

function isOperatorRootUrl(url: string, operatorId: string): boolean {
  const path = url.split(/[?#]/, 1)[0].replace(/\/+$/, '');
  return path === `/operator/${operatorId}`;
}

function findRouteParam(route: ActivatedRouteSnapshot, name: string): string | null {
  let current: ActivatedRouteSnapshot | null = route;
  while (current) {
    const value = current.paramMap.get(name);
    if (value) {
      return value;
    }
    current = current.parent;
  }
  return null;
}
