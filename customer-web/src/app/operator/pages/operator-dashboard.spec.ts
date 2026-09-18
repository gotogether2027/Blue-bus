import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { environment } from '../../../environments/environment';
import {
  operatorBusFixture,
  operatorMembershipFixture,
  operatorProfileFixture,
  operatorRouteFixture,
  operatorTripFixture,
  operatorTripSeatInventoryFixture
} from '../../../testing/operator-fixtures';
import { routes } from '../../app.routes';
import { AuthService } from '../../core/auth/auth.service';
import {
  operatorDashboardToday,
  upcomingTrips
} from '../components/operator-dashboard-summary';
import { OperatorMembership, OperatorTrip } from '../models/operator.models';
import { OPERATOR_ROUTES } from '../operator.routes';
import { OperatorContextService } from '../services/operator-context.service';
import { OperatorDashboardPageComponent } from './operator-dashboard/operator-dashboard.page';

describe('operator operations dashboard', () => {
  const base = `${environment.apiBaseUrl}/operator`;

  afterEach(() => {
    const http = TestBed.inject(HttpTestingController, null, { optional: true });
    http?.verify();
  });

  it('loads the selected operator with the four existing read APIs', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();

    const pending = captureDashboard(setup.http, 'operator-1');
    const requestList = [pending.profile, pending.buses, pending.routes, pending.trips];
    expect(requestList.map((request) => request.request.url).sort()).toEqual(
      [
        `${base}/operator-1`,
        `${base}/operator-1/buses`,
        `${base}/operator-1/routes`,
        `${base}/operator-1/trips`
      ].sort()
    );
    expect(requestList.every((request) => request.request.method === 'GET')).toBeTrue();
    expect(
      requestList.every((request) => request.request.params.keys().length === 0)
    ).toBeTrue();
    expect(
      requestList.some((request) => request.request.url.includes('/bookings'))
    ).toBeFalse();
    expect(
      requestList.some((request) => request.request.url.includes('/inventory'))
    ).toBeFalse();
    expect(
      requestList.some((request) => request.request.url.includes('/notifications'))
    ).toBeFalse();
    expect(
      requestList.some((request) => request.request.url.includes('/outbox'))
    ).toBeFalse();
    expect(
      requestList.some((request) => !request.request.url.startsWith(`${base}/operator-1`))
    ).toBeFalse();

    const trip = futureTrip();
    pending.profile.flush(operatorProfileFixture({ id: 'operator-1' }));
    pending.buses.flush([operatorBusFixture()]);
    pending.routes.flush([operatorRouteFixture()]);
    pending.trips.flush([trip]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Coastal Travels');
    expect(text).toContain('operator-1');
    expect(text).toContain('Operations overview');
    expect(text).toContain('Operator admin');
    expect(fixture.componentInstance.operator?.id).toBe('operator-1');
  });

  it('counts only active buses and routes for the selected operator', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1', {
      buses: [
        operatorBusFixture(),
        operatorBusFixture({ id: 'bus-2', status: 'INACTIVE', displayName: 'Parked Coach' }),
        operatorBusFixture({
          id: 'bus-3',
          operatorId: 'operator-2',
          displayName: 'Inland Coach',
          status: 'ACTIVE'
        })
      ],
      routes: [
        operatorRouteFixture(),
        operatorRouteFixture({ id: 'route-2', status: 'INACTIVE', code: 'OLD-RTE' }),
        operatorRouteFixture({
          id: 'route-3',
          operatorId: 'operator-2',
          code: 'INL-EXP',
          status: 'ACTIVE'
        })
      ],
      trips: []
    });
    fixture.detectChanges();

    expect(fixture.componentInstance.activeBuses).toBe(1);
    expect(fixture.componentInstance.activeRoutes).toBe(1);
    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Active buses');
    expect(text).toContain('Active routes');
    expect(text).not.toContain('Inland Coach');
    expect(text).not.toContain('INL-EXP');
  });

  it('summarizes today and upcoming trips from the unfiltered trip list', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    const upcoming = futureTrip({
      id: 'trip-upcoming',
      seatInventory: [
        operatorTripSeatInventoryFixture(),
        operatorTripSeatInventoryFixture({
          id: 'inventory-2',
          seatNumber: 'U2',
          columnNumber: 2,
          physicalStatus: 'BLOCKED',
          blockReason: 'Maintenance'
        })
      ]
    });
    const today = operatorTripFixture({
      id: 'trip-today',
      serviceDate: operatorDashboardToday(),
      scheduledDepartureAt: hoursFromNow(-3),
      scheduledArrivalAt: hoursFromNow(3),
      status: 'ON_SALE',
      seatInventory: []
    });
    flushDashboard(setup.http, 'operator-1', {
      buses: [operatorBusFixture()],
      routes: [operatorRouteFixture()],
      trips: [
        upcoming,
        today,
        futureTrip({ id: 'trip-cancelled', status: 'CANCELLED' }),
        futureTrip({ id: 'trip-departed', status: 'DEPARTED' }),
        operatorTripFixture({
          id: 'trip-past',
          serviceDate: '2020-01-01',
          scheduledDepartureAt: hoursFromNow(-48),
          scheduledArrivalAt: hoursFromNow(-42),
          status: 'SCHEDULED'
        })
      ]
    });
    fixture.detectChanges();

    expect(fixture.componentInstance.todayTrips.map((trip) => trip.id)).toEqual(['trip-today']);
    expect(fixture.componentInstance.upcoming.map((trip) => trip.id)).toEqual(['trip-upcoming']);
    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Coastal Sleeper');
    expect(text).toContain('HYD-VJA');
    expect(text).toContain('SCHEDULED');
    expect(text).toContain('1 available · 1 blocked');
    expect(text).toContain('Physical seat status is not booking occupancy');
    expect(text).not.toContain('trip-cancelled');
    expect(text).not.toContain('trip-past');
  });

  it('omits operator-wide booking, passenger, and blocked-seat totals', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1', {
      buses: [operatorBusFixture()],
      routes: [operatorRouteFixture()],
      trips: [futureTrip()]
    });
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('no operator-wide booking summary API');
    expect(text).toContain('HELD and BOOKED allocations are not returned by this API');
    expect(text).not.toContain('Confirmed bookings');
    expect(text).not.toContain('Pending-payment bookings');
    expect(text).not.toContain('Passenger count');
    expect(text).not.toContain('Blocked physical seats');
    expect(statusLabels(fixture.nativeElement)).not.toContain('HELD');
    expect(statusLabels(fixture.nativeElement)).not.toContain('BOOKED');
    setup.http.expectNone((request) => request.url.includes('/bookings'));
    setup.http.expectNone((request) => request.url.includes('/inventory'));
  });

  it('links upcoming trips to the existing trip, inventory, and bookings pages', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1', {
      buses: [operatorBusFixture()],
      routes: [operatorRouteFixture()],
      trips: [futureTrip({ id: 'trip-1' })]
    });
    fixture.detectChanges();

    const hrefs = linkHrefs(fixture.nativeElement);
    expect(hrefs).toContain('/operator/operator-1/trips/trip-1');
    expect(hrefs).toContain('/operator/operator-1/trips/trip-1/inventory');
    expect(hrefs).toContain('/operator/operator-1/trips/trip-1/bookings');
  });

  it('derives a blocked-seat operational alert from nested trip inventory', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1', {
      buses: [operatorBusFixture()],
      routes: [operatorRouteFixture()],
      trips: [
        futureTrip({
          id: 'trip-1',
          seatInventory: [
            operatorTripSeatInventoryFixture(),
            operatorTripSeatInventoryFixture({
              id: 'inventory-2',
              seatNumber: 'U2',
              physicalStatus: 'BLOCKED'
            }),
            operatorTripSeatInventoryFixture({
              id: 'inventory-3',
              seatNumber: 'U3',
              physicalStatus: 'BLOCKED'
            })
          ]
        })
      ]
    });
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Operational alerts');
    expect(text).toContain('not persisted notifications');
    expect(text).toContain('WARNING');
    expect(text).toContain('2 physical seats are blocked on the upcoming Coastal Sleeper trip.');
    expect(text).not.toContain('CRITICAL');
    expect(linkHrefs(fixture.nativeElement)).toContain('/operator/operator-1/trips/trip-1');
    expect(linkHrefs(fixture.nativeElement)).toContain(
      '/operator/operator-1/trips/trip-1/inventory'
    );
    setup.http.expectNone((request) => request.url.includes('/notifications'));
    setup.http.expectNone((request) => request.url.includes('/outbox'));
    setup.http.expectNone((request) => request.url.includes('/bookings'));
    setup.http.expectNone((request) => request.url.includes('/inventory'));
  });

  it('derives a cancelled future-trip alert with a trip detail link', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1', {
      buses: [operatorBusFixture()],
      routes: [operatorRouteFixture()],
      trips: [futureTrip({ id: 'trip-cancelled', status: 'CANCELLED' })]
    });
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('The Coastal Sleeper trip with a future departure is cancelled.');
    expect(text).toContain('No upcoming trips are listed for this operator.');
    expect(linkHrefs(fixture.nativeElement)).toContain(
      '/operator/operator-1/trips/trip-cancelled'
    );
  });

  it('shows an informational empty-schedule signal without inventing notifications', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1', {
      buses: [operatorBusFixture()],
      routes: [operatorRouteFixture()],
      trips: []
    });
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('INFO');
    expect(text).toContain('No upcoming trips are listed for this operator.');
    expect(text).not.toContain('WARNING');
    expect(text).not.toContain('unread');
    expect(text).not.toContain('email sent');
    expect(text).not.toContain('SMS sent');
    expect(text).not.toContain('WhatsApp');
    expect(linkHrefs(fixture.nativeElement)).toContain('/operator/operator-1/trips');
  });

  it('does not show false alerts for a healthy upcoming trip', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1', {
      buses: [operatorBusFixture()],
      routes: [operatorRouteFixture()],
      trips: [futureTrip()]
    });
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('No operational alerts from the current operator lists.');
    expect(text).not.toContain('physical seat is blocked');
    expect(text).not.toContain('future departure is cancelled');
    expect(fixture.componentInstance.alerts).toEqual([]);
  });

  it('lets operator staff view the same derived alerts without write actions', async () => {
    const setup = await configure(false);
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1', {
      buses: [operatorBusFixture()],
      routes: [operatorRouteFixture()],
      trips: [
        futureTrip({
          seatInventory: [operatorTripSeatInventoryFixture({ physicalStatus: 'BLOCKED' })]
        })
      ]
    });
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Operator staff');
    expect(text).toContain('1 physical seat is blocked on the upcoming Coastal Sleeper trip.');
    expect(text).not.toContain('Create bus');
    expect((fixture.nativeElement as HTMLElement).querySelector('form')).toBeNull();
  });

  it('exposes quick-action links to existing operator areas', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1');
    fixture.detectChanges();

    const hrefs = linkHrefs(fixture.nativeElement);
    expect(hrefs).toContain('/operator/operator-1/buses');
    expect(hrefs).toContain('/operator/operator-1/routes');
    expect(hrefs).toContain('/operator/operator-1/trips');
    expect(hrefs).toContain('/operator/operator-1/members');
    expect(hrefs).toContain('/operator/operator-1/settings');
    expect(pageText(fixture.nativeElement)).toContain('Quick actions');
  });

  it('renders empty states when the selected operator has no resources', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1', {
      buses: [],
      routes: [],
      trips: []
    });
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('No active buses');
    expect(text).toContain('No active routes');
    expect(text).toContain('No upcoming trips');
    expect(fixture.componentInstance.activeBuses).toBe(0);
    expect(fixture.componentInstance.upcoming.length).toBe(0);
  });

  it('shows a loading state until the dashboard responses arrive', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.loading).toBeTrue();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[aria-label="Loading operator dashboard"]'
      )
    ).not.toBeNull();

    flushDashboard(setup.http, 'operator-1');
    fixture.detectChanges();
    expect(fixture.componentInstance.loading).toBeFalse();
  });

  it('lets operator staff view the dashboard without introducing write actions', async () => {
    const setup = await configure(false);
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1', {
      buses: [operatorBusFixture()],
      routes: [operatorRouteFixture()],
      trips: [futureTrip()]
    });
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Coastal Travels');
    expect(text).toContain('Operator staff');
    expect(text).toContain('Upcoming trips');
    expect(text).not.toContain('Create bus');
    expect(text).not.toContain('Add member');
    expect(text).not.toContain('Save support contact');
    expect((fixture.nativeElement as HTMLElement).querySelector('form')).toBeNull();
  });

  it('lets operator admins view the same read-only operations overview', async () => {
    const setup = await configure(true);
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Operator admin');
    expect(text).not.toContain('Create bus');
    expect(text).not.toContain('Add member');
    expect((fixture.nativeElement as HTMLElement).querySelector('form')).toBeNull();
  });

  it('maps a backend 403 to the existing operator access-denied state', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboardError(setup.http, 'operator-1', 403, 'Forbidden');
    fixture.detectChanges();

    expect(fixture.componentInstance.operator).toBeNull();
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('maps a backend 404 to the existing resource-not-found state', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboardError(setup.http, 'operator-1', 404, 'Not Found');
    fixture.detectChanges();

    expect(fixture.componentInstance.operator).toBeNull();
    expect(pageText(fixture.nativeElement)).toContain('Resource not found');
  });

  it('does not issue child API requests when the selected operator is unavailable', async () => {
    const setup = await configure();
    setup.selectedOperatorId.set(null);
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();

    setup.http.expectNone(() => true);
    expect(fixture.componentInstance.operator).toBeNull();
    expect(fixture.componentInstance.alerts).toEqual([]);
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('clears previous dashboard data before loading a newly selected operator', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1', {
      buses: [operatorBusFixture()],
      routes: [operatorRouteFixture()],
      trips: [futureTrip()]
    });
    expect(fixture.componentInstance.operator?.displayName).toBe('Coastal Travels');
    expect(fixture.componentInstance.activeBuses).toBe(1);

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.operator).toBeNull();
    expect(fixture.componentInstance.buses).toEqual([]);
    expect(fixture.componentInstance.trips).toEqual([]);
    expect(fixture.componentInstance.upcoming).toEqual([]);
    expect(fixture.componentInstance.alerts).toEqual([]);
    expect(fixture.componentInstance.activeBuses).toBe(0);

    flushDashboard(setup.http, 'operator-2', {
      profile: operatorProfileFixture({
        id: 'operator-2',
        displayName: 'Inland Express',
        legalName: 'Inland Express Private Limited'
      }),
      buses: [],
      routes: [],
      trips: []
    });
    fixture.detectChanges();

    expect(fixture.componentInstance.operator?.id).toBe('operator-2');
    expect(pageText(fixture.nativeElement)).toContain('Inland Express');
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Travels');
  });

  it('ignores a stale dashboard response after the operator changes', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    const first = captureDashboard(setup.http, 'operator-1');

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.operator).toBeNull();

    first.profile.flush(operatorProfileFixture());
    first.buses.flush([operatorBusFixture()]);
    first.routes.flush([operatorRouteFixture()]);
    first.trips.flush([futureTrip()]);
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Travels');
    expect(fixture.componentInstance.alerts).toEqual([]);

    flushDashboard(setup.http, 'operator-2', {
      profile: operatorProfileFixture({
        id: 'operator-2',
        displayName: 'Inland Express',
        legalName: 'Inland Express Private Limited'
      })
    });
    fixture.detectChanges();

    expect(fixture.componentInstance.operator?.id).toBe('operator-2');
    expect(pageText(fixture.nativeElement)).toContain('Inland Express');
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Travels');
  });

  it('does not keep child resources when the profile operator ID does not match', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1', {
      profile: operatorProfileFixture({ id: 'operator-9' }),
      buses: [operatorBusFixture()],
      routes: [operatorRouteFixture()],
      trips: [futureTrip()]
    });
    fixture.detectChanges();

    expect(fixture.componentInstance.operator).toBeNull();
    expect(fixture.componentInstance.buses).toEqual([]);
    expect(pageText(fixture.nativeElement)).toContain('Resource not found');
  });

  it('keeps the dashboard on the existing operator membership-guarded route', () => {
    const operatorArea = OPERATOR_ROUTES.find((route) => route.path === ':operatorId');
    const dashboard = operatorArea?.children?.find((route) => route.path === '');

    expect(operatorArea?.canActivate).toBeDefined();
    expect(dashboard?.component).toBe(OperatorDashboardPageComponent);
    expect(dashboard?.canActivate).toBeUndefined();
  });

  it('leaves customer profile and booking/payment routes unchanged', () => {
    const customerShell = routes.find((route) => route.path === '');
    const customerPaths = (customerShell?.children ?? []).map((route) => route.path);

    expect(customerPaths).toEqual([
      '',
      'search',
      'login',
      'register',
      'bookings',
      'bookings/:bookingId/confirmation',
      'bookings/:bookingId',
      'profile',
      'trips/:tripId/seats',
      'checkout/:holdId/passengers',
      'checkout/:holdId/review',
      'payment/:bookingId'
    ]);
  });

  it('limits upcoming-trip previews without inventing extra API calls', () => {
    const trips = Array.from({ length: 7 }, (_, index) =>
      futureTrip({
        id: `trip-${index + 1}`,
        scheduledDepartureAt: hoursFromNow(index + 1),
        scheduledArrivalAt: hoursFromNow(index + 7)
      })
    );
    expect(upcomingTrips(trips, 'operator-1').length).toBe(7);
    expect(upcomingTrips(trips, 'operator-1', Date.now(), 5).map((trip) => trip.id)).toEqual([
      'trip-1',
      'trip-2',
      'trip-3',
      'trip-4',
      'trip-5'
    ]);
  });

  it('shows a bounded upcoming-trip preview from the already-fetched list', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorDashboardPageComponent);
    fixture.detectChanges();
    flushDashboard(setup.http, 'operator-1', {
      buses: [operatorBusFixture()],
      routes: [operatorRouteFixture()],
      trips: Array.from({ length: 7 }, (_, index) =>
        futureTrip({
          id: `trip-${index + 1}`,
          scheduledDepartureAt: hoursFromNow(index + 1),
          scheduledArrivalAt: hoursFromNow(index + 7)
        })
      )
    });
    fixture.detectChanges();

    expect(fixture.componentInstance.upcoming.length).toBe(7);
    expect(fixture.componentInstance.upcomingPreview.length).toBe(5);
    expect(pageText(fixture.nativeElement)).toContain(
      'Showing the next 5 of 7 upcoming trips from the operator trip list.'
    );
    setup.http.expectNone((request) => request.url.includes('/bookings'));
  });

  async function configure(canManage = true): Promise<{
    http: HttpTestingController;
    selectedOperatorId: WritableSignal<string | null>;
  }> {
    const selectedOperatorId = signal<string | null>('operator-1');
    const canManageOperator = signal(canManage);
    const membership = signal<OperatorMembership | null>(
      operatorMembershipFixture({
        role: canManage ? 'OPERATOR_ADMIN' : 'OPERATOR_STAFF'
      })
    );

    await TestBed.configureTestingModule({
      imports: [OperatorDashboardPageComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: OperatorContextService,
          useValue: {
            selectedOperatorId: selectedOperatorId.asReadonly(),
            canManageOperator: canManageOperator.asReadonly(),
            currentMembership: membership.asReadonly()
          }
        },
        {
          provide: AuthService,
          useValue: { clearSession: jasmine.createSpy('clearSession') }
        }
      ]
    }).compileComponents();

    return {
      http: TestBed.inject(HttpTestingController),
      selectedOperatorId
    };
  }

  function captureDashboard(
    http: HttpTestingController,
    operatorId: string
  ): {
    profile: TestRequest;
    buses: TestRequest;
    routes: TestRequest;
    trips: TestRequest;
  } {
    return {
      profile: http.expectOne(`${base}/${operatorId}`),
      buses: http.expectOne(`${base}/${operatorId}/buses`),
      routes: http.expectOne(`${base}/${operatorId}/routes`),
      trips: http.expectOne(`${base}/${operatorId}/trips`)
    };
  }

  function flushDashboard(
    http: HttpTestingController,
    operatorId: string,
    payload: {
      profile?: ReturnType<typeof operatorProfileFixture>;
      buses?: ReturnType<typeof operatorBusFixture>[];
      routes?: ReturnType<typeof operatorRouteFixture>[];
      trips?: OperatorTrip[];
    } = {}
  ): void {
    const requests = captureDashboard(http, operatorId);
    requests.profile.flush(payload.profile ?? operatorProfileFixture({ id: operatorId }));
    requests.buses.flush(payload.buses ?? []);
    requests.routes.flush(payload.routes ?? []);
    requests.trips.flush(payload.trips ?? []);
  }

  function flushDashboardError(
    http: HttpTestingController,
    operatorId: string,
    status: number,
    statusText: string
  ): void {
    const requests = captureDashboard(http, operatorId);
    requests.buses.flush([]);
    requests.routes.flush([]);
    requests.trips.flush([]);
    requests.profile.flush({}, { status, statusText });
  }

  function futureTrip(overrides: Partial<OperatorTrip> = {}): OperatorTrip {
    const departure = new Date(Date.now() + 24 * 60 * 60 * 1000);
    return operatorTripFixture({
      scheduledDepartureAt: departure.toISOString(),
      scheduledArrivalAt: new Date(departure.getTime() + 6 * 60 * 60 * 1000).toISOString(),
      serviceDate: operatorDashboardToday('Asia/Kolkata', departure),
      ...overrides
    });
  }

  function hoursFromNow(hours: number): string {
    return new Date(Date.now() + hours * 60 * 60 * 1000).toISOString();
  }

  function pageText(element: HTMLElement): string {
    return element.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  function linkHrefs(element: HTMLElement): Array<string | null> {
    return Array.from(element.querySelectorAll<HTMLAnchorElement>('a')).map((anchor) =>
      anchor.getAttribute('href')
    );
  }

  function statusLabels(element: HTMLElement): string[] {
    return Array.from(element.querySelectorAll('.badge')).map(
      (badge) => badge.textContent?.trim() ?? ''
    );
  }
});
