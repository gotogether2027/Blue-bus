import { ActivatedRouteSnapshot, DetachedRouteHandle, RouteReuseStrategy } from '@angular/router';

const OPERATOR_RESOURCE_PARAMS = ['busId', 'routeId', 'tripId', 'bookingId'] as const;

/**
 * Prevents Angular from reusing an operator page when the operator or resource
 * identity in the URL changes. Default reuse compares routeConfig only, which
 * would leave operator A data on screen after navigating to operator B.
 */
export class OperatorAwareRouteReuseStrategy implements RouteReuseStrategy {
  shouldDetach(_route: ActivatedRouteSnapshot): boolean {
    return false;
  }

  store(_route: ActivatedRouteSnapshot, _handle: DetachedRouteHandle | null): void {}

  shouldAttach(_route: ActivatedRouteSnapshot): boolean {
    return false;
  }

  retrieve(_route: ActivatedRouteSnapshot): DetachedRouteHandle | null {
    return null;
  }

  shouldReuseRoute(future: ActivatedRouteSnapshot, curr: ActivatedRouteSnapshot): boolean {
    if (future.routeConfig !== curr.routeConfig) {
      return false;
    }

    const futureOperatorId = routeParam(future, 'operatorId');
    const currentOperatorId = routeParam(curr, 'operatorId');
    if (futureOperatorId && currentOperatorId && futureOperatorId !== currentOperatorId) {
      return false;
    }
    if (!futureOperatorId || !currentOperatorId) {
      return true;
    }

    return OPERATOR_RESOURCE_PARAMS.every((name) => {
      const nextValue = routeParam(future, name);
      const currentValue = routeParam(curr, name);
      return !nextValue || !currentValue || nextValue === currentValue;
    });
  }
}

export function routeParam(route: ActivatedRouteSnapshot, name: string): string | null {
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
