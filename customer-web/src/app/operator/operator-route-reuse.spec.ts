import { ActivatedRouteSnapshot, convertToParamMap } from '@angular/router';
import { OperatorAwareRouteReuseStrategy, routeParam } from './operator-route-reuse';

describe('OperatorAwareRouteReuseStrategy', () => {
  const strategy = new OperatorAwareRouteReuseStrategy();
  const dashboardConfig = { path: '' };
  const busesConfig = { path: 'buses' };
  const bookingsConfig = { path: 'bookings' };
  const customerBookingsConfig = { path: 'bookings' };

  it('reuses the same operator child route when identity params are unchanged', () => {
    const current = snapshot({ operatorId: 'operator-1' }, busesConfig);
    const future = snapshot({ operatorId: 'operator-1' }, busesConfig);

    expect(strategy.shouldReuseRoute(future, current)).toBeTrue();
  });

  it('does not reuse a page when the operator ID changes', () => {
    const current = snapshot({ operatorId: 'operator-1' }, dashboardConfig);
    const future = snapshot({ operatorId: 'operator-2' }, dashboardConfig);

    expect(strategy.shouldReuseRoute(future, current)).toBeFalse();
  });

  it('does not reuse trip-scoped pages when the trip ID changes', () => {
    const current = snapshot({ operatorId: 'operator-1', tripId: 'trip-1' }, bookingsConfig);
    const future = snapshot({ operatorId: 'operator-1', tripId: 'trip-2' }, bookingsConfig);

    expect(strategy.shouldReuseRoute(future, current)).toBeFalse();
  });

  it('reuses customer routes that do not carry operator identity', () => {
    const current = snapshot({ bookingId: 'booking-1' }, customerBookingsConfig);
    const future = snapshot({ bookingId: 'booking-2' }, customerBookingsConfig);

    expect(strategy.shouldReuseRoute(future, current)).toBeTrue();
  });

  it('does not reuse operator booking detail when the booking ID changes', () => {
    const current = snapshot(
      { operatorId: 'operator-1', tripId: 'trip-1', bookingId: 'booking-1' },
      bookingsConfig
    );
    const future = snapshot(
      { operatorId: 'operator-1', tripId: 'trip-1', bookingId: 'booking-2' },
      bookingsConfig
    );

    expect(strategy.shouldReuseRoute(future, current)).toBeFalse();
  });

  it('reads nested operator identity from parent snapshots', () => {
    const parent = snapshot({ operatorId: 'operator-1' }, { path: ':operatorId' });
    const child = snapshot({ tripId: 'trip-1' }, bookingsConfig, parent);

    expect(routeParam(child, 'operatorId')).toBe('operator-1');
    expect(routeParam(child, 'tripId')).toBe('trip-1');
  });
});

function snapshot(
  params: Record<string, string>,
  routeConfig: { path: string },
  parent: ActivatedRouteSnapshot | null = null
): ActivatedRouteSnapshot {
  return {
    routeConfig,
    paramMap: convertToParamMap(params),
    parent
  } as unknown as ActivatedRouteSnapshot;
}
