import { routes } from './app.routes';

describe('application routes', () => {
  it('keeps all customer routes unchanged while adding the operator area', () => {
    const customerShell = routes.find((route) => route.path === '');
    const customerPaths = (customerShell?.children ?? []).map((route) => route.path);

    expect(customerPaths).toEqual([
      '',
      'search',
      'login',
      'register',
      'bookings',
      'bookings/:bookingId/confirmation',
      'bookings/:bookingId/ticket',
      'bookings/:bookingId',
      'profile',
      'trips/:tripId/seats',
      'checkout/:holdId/passengers',
      'checkout/:holdId/review',
      'payment/:bookingId'
    ]);
    expect(routes.find((route) => route.path === 'operator')?.loadChildren).toBeDefined();
    expect(routes[routes.length - 1].path).toBe('**');
  });
});
