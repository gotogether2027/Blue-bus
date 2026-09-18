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
  operatorRouteFixture,
  operatorTripFixture,
  operatorTripSeatInventoryFixture
} from '../../../testing/operator-fixtures';
import { routes } from '../../app.routes';
import { AuthService } from '../../core/auth/auth.service';
import { OPERATOR_ROUTES } from '../operator.routes';
import { OperatorMembership, OperatorTrip } from '../models/operator.models';
import { OperatorContextService } from '../services/operator-context.service';
import { OperatorReportsPageComponent } from './operator-reports/operator-reports.page';

describe('operator reports', () => {
  const base = `${environment.apiBaseUrl}/operator`;

  afterEach(() => {
    const http = TestBed.inject(HttpTestingController, null, { optional: true });
    http?.verify();
  });

  it('loads reports with the selected operator ID on the three existing list APIs', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorReportsPageComponent);
    fixture.detectChanges();
    const pending = captureReports(setup.http, 'operator-1');
    const requestList = [pending.buses, pending.routes, pending.trips];
    expect(requestList.map((request) => request.request.url).sort()).toEqual([
      `${base}/operator-1/buses`,
      `${base}/operator-1/routes`,
      `${base}/operator-1/trips`
    ].sort());
    expect(requestList.every((request) => request.request.method === 'GET')).toBeTrue();
    expect(requestList.every((request) => request.request.params.keys().length === 0)).toBeTrue();
    expect(requestList.some((request) => request.request.url.includes('/bookings'))).toBeFalse();
    expect(requestList.some((request) => request.request.url.includes('/inventory'))).toBeFalse();

    pending.buses.flush([operatorBusFixture()]);
    pending.routes.flush([operatorRouteFixture()]);
    pending.trips.flush([futureTrip()]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Reports');
    expect(text).toContain('Active buses');
    expect(text).toContain('Trip activity based on service date');
    expect(fixture.componentInstance.activeBuses).toBe(1);
    expect(fixture.componentInstance.activeRoutes).toBe(1);
  });

  it('summarizes supported trip activity and physical inventory from the trip list', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorReportsPageComponent);
    fixture.detectChanges();
    flushReports(setup.http, 'operator-1', {
      buses: [operatorBusFixture()],
      routes: [operatorRouteFixture()],
      trips: [
        futureTrip({
          id: 'trip-scheduled',
          status: 'SCHEDULED',
          seatInventory: [
            operatorTripSeatInventoryFixture(),
            operatorTripSeatInventoryFixture({
              id: 'inventory-2',
              seatNumber: 'U2',
              physicalStatus: 'BLOCKED'
            })
          ]
        }),
        operatorTripFixture({
          id: 'trip-completed',
          status: 'COMPLETED',
          scheduledDepartureAt: hoursFromNow(-48),
          scheduledArrivalAt: hoursFromNow(-42),
          seatInventory: [operatorTripSeatInventoryFixture({ id: 'inventory-3', physicalStatus: 'AVAILABLE' })]
        }),
        operatorTripFixture({
          id: 'trip-cancelled',
          status: 'CANCELLED',
          scheduledDepartureAt: hoursFromNow(12),
          scheduledArrivalAt: hoursFromNow(18),
          seatInventory: []
        })
      ]
    });
    fixture.detectChanges();

    expect(fixture.componentInstance.scheduledTrips).toBe(1);
    expect(fixture.componentInstance.completedTrips).toBe(1);
    expect(fixture.componentInstance.cancelledTrips).toBe(1);
    expect(fixture.componentInstance.upcomingTripCount).toBe(1);
    expect(fixture.componentInstance.listedPhysicalAvailable).toBe(2);
    expect(fixture.componentInstance.listedPhysicalBlocked).toBe(1);

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Coastal Sleeper');
    expect(text).toContain('HYD-VJA');
    expect(text).toContain('SCHEDULED');
    expect(text).toContain('COMPLETED');
    expect(text).toContain('CANCELLED');
    expect(text).toContain('Physical available seats');
    expect(text).toContain('Not passenger occupancy');
    expect(text).toContain('Not booked or occupied');
  });

  it('does not fabricate financial or operator-wide booking metrics', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorReportsPageComponent);
    fixture.detectChanges();
    flushReports(setup.http, 'operator-1', {
      buses: [operatorBusFixture()],
      routes: [operatorRouteFixture()],
      trips: [futureTrip()]
    });
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Not available in operator reporting');
    expect(text).toContain('no operator-wide booking aggregate API');
    expect(text).not.toContain('Revenue');
    expect(text).not.toContain('Net revenue');
    expect(text).not.toContain('Settlement');
    expect(text).not.toContain('Commission');
    expect(text).not.toContain('Payment success');
    expect(text).not.toContain('Confirmed bookings');
    expect(text).not.toContain('Passenger count');
    setup.http.expectNone((request) => request.url.includes('/bookings'));
    setup.http.expectNone((request) => request.url.includes('/inventory'));
    setup.http.expectNone((request) => request.url.includes('/payments'));
    setup.http.expectNone((request) => request.url.includes('/refunds'));
    setup.http.expectNone((request) => request.url.includes('/search'));
    setup.http.expectNone((request) => request.url.includes('/holds'));
    setup.http.expectNone((request) => request.url.includes('/tickets'));
  });

  it('applies the trip-list serviceDate filter without inventing booking date filters', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorReportsPageComponent);
    fixture.detectChanges();
    flushReports(setup.http, 'operator-1', { trips: [futureTrip()] });
    fixture.componentInstance.serviceDateFilter = '2026-12-18';
    fixture.componentInstance.load();

    const requests = captureReports(setup.http, 'operator-1', { serviceDate: '2026-12-18' });
    expect(requests.trips.request.params.get('serviceDate')).toBe('2026-12-18');
    expect(requests.buses.request.params.keys().length).toBe(0);
    expect(requests.routes.request.params.keys().length).toBe(0);
    requests.buses.flush([operatorBusFixture()]);
    requests.routes.flush([operatorRouteFixture()]);
    requests.trips.flush([operatorTripFixture({ serviceDate: '2026-12-18' })]);
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain('Trip activity based on service date');
    expect(pageText(fixture.nativeElement)).toContain('does not apply to buses, routes, bookings, or payments');
  });

  it('links each report row to the existing trip detail page', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorReportsPageComponent);
    fixture.detectChanges();
    flushReports(setup.http, 'operator-1', {
      trips: [futureTrip({ id: 'trip-1' })]
    });
    fixture.detectChanges();

    const hrefs = linkHrefs(fixture.nativeElement);
    expect(hrefs).toContain('/operator/operator-1/trips/trip-1');
    expect(hrefs).toContain('/operator/operator-1/trips');
    expect(hrefs).toContain('/operator/operator-1/buses');
    expect(hrefs).toContain('/operator/operator-1/routes');
  });

  it('shows an empty trip table without inventing trip rows', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorReportsPageComponent);
    fixture.detectChanges();
    flushReports(setup.http, 'operator-1', { trips: [] });
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain('No trips');
    expect(fixture.componentInstance.trips).toEqual([]);
    expect(fixture.componentInstance.listedPhysicalBlocked).toBe(0);
  });

  it('shows a loading state until report responses arrive', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorReportsPageComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.loading).toBeTrue();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[aria-label="Loading operator reports"]')
    ).not.toBeNull();
    flushReports(setup.http, 'operator-1');
    fixture.detectChanges();
    expect(fixture.componentInstance.loading).toBeFalse();
  });

  it('lets operator staff view reports without write actions', async () => {
    const setup = await configure(false);
    const fixture = TestBed.createComponent(OperatorReportsPageComponent);
    fixture.detectChanges();
    flushReports(setup.http, 'operator-1', { trips: [futureTrip()] });
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Operator staff');
    expect(text).toContain('Listed trips');
    expect(text).not.toContain('Create trip');
    expect(text).not.toContain('Export');
    expect((fixture.nativeElement as HTMLElement).querySelector('form')).toBeNull();
  });

  it('lets operator admins view the same read-only reports', async () => {
    const setup = await configure(true);
    const fixture = TestBed.createComponent(OperatorReportsPageComponent);
    fixture.detectChanges();
    flushReports(setup.http, 'operator-1');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Operator admin');
    expect(text).not.toContain('Create trip');
    expect(text).not.toContain('Export CSV');
  });

  it('maps a backend 403 to the existing access-denied state', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorReportsPageComponent);
    fixture.detectChanges();
    const requests = captureReports(setup.http, 'operator-1');
    requests.buses.flush([]);
    requests.routes.flush([]);
    requests.trips.flush({}, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('shows a recoverable error when a report API fails', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorReportsPageComponent);
    fixture.detectChanges();
    const requests = captureReports(setup.http, 'operator-1');
    requests.buses.flush([]);
    requests.routes.flush([]);
    requests.trips.flush({}, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Operator data is unavailable');
    expect(text).toContain('Try again');
  });

  it('does not issue child API requests when the selected operator is unavailable', async () => {
    const setup = await configure();
    setup.selectedOperatorId.set(null);
    const fixture = TestBed.createComponent(OperatorReportsPageComponent);
    fixture.detectChanges();

    setup.http.expectNone(() => true);
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('clears previous report data before loading a newly selected operator', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorReportsPageComponent);
    fixture.detectChanges();
    flushReports(setup.http, 'operator-1', {
      buses: [operatorBusFixture()],
      trips: [futureTrip()]
    });
    expect(fixture.componentInstance.activeBuses).toBe(1);

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.trips).toEqual([]);
    expect(fixture.componentInstance.activeBuses).toBe(0);

    flushReports(setup.http, 'operator-2', {
      buses: [],
      routes: [],
      trips: []
    });
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Sleeper');
  });

  it('ignores a stale report response after the operator changes', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorReportsPageComponent);
    fixture.detectChanges();
    const first = captureReports(setup.http, 'operator-1');

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.trips).toEqual([]);

    first.buses.flush([operatorBusFixture()]);
    first.routes.flush([operatorRouteFixture()]);
    first.trips.flush([futureTrip()]);
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Sleeper');

    flushReports(setup.http, 'operator-2', { trips: [] });
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('No trips');
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Sleeper');
  });

  it('exposes reports under the operator membership guard', () => {
    const operatorArea = OPERATOR_ROUTES.find((route) => route.path === ':operatorId');
    const childPaths = (operatorArea?.children ?? []).map((route) => route.path);
    expect(operatorArea?.canActivate).toBeDefined();
    expect(childPaths).toContain('reports');
    expect(operatorArea?.children?.find((route) => route.path === 'reports')?.component).toBe(
      OperatorReportsPageComponent
    );
    expect(operatorArea?.children?.find((route) => route.path === 'reports')?.canActivate).toBeUndefined();
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
      imports: [OperatorReportsPageComponent],
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

  function captureReports(
    http: HttpTestingController,
    operatorId: string,
    options: { serviceDate?: string } = {}
  ): { buses: TestRequest; routes: TestRequest; trips: TestRequest } {
    return {
      buses: http.expectOne(`${base}/${operatorId}/buses`),
      routes: http.expectOne(`${base}/${operatorId}/routes`),
      trips: http.expectOne(
        (request) =>
          request.method === 'GET' &&
          request.url === `${base}/${operatorId}/trips` &&
          (options.serviceDate
            ? request.params.get('serviceDate') === options.serviceDate
            : request.params.keys().length === 0)
      )
    };
  }

  function flushReports(
    http: HttpTestingController,
    operatorId: string,
    payload: {
      buses?: ReturnType<typeof operatorBusFixture>[];
      routes?: ReturnType<typeof operatorRouteFixture>[];
      trips?: OperatorTrip[];
    } = {}
  ): void {
    const requests = captureReports(http, operatorId);
    requests.buses.flush(payload.buses ?? [operatorBusFixture({ operatorId })]);
    requests.routes.flush(payload.routes ?? [operatorRouteFixture({ operatorId })]);
    requests.trips.flush(payload.trips ?? []);
  }

  function futureTrip(overrides: Partial<OperatorTrip> = {}): OperatorTrip {
    const departure = new Date(Date.now() + 24 * 60 * 60 * 1000);
    return operatorTripFixture({
      scheduledDepartureAt: departure.toISOString(),
      scheduledArrivalAt: new Date(departure.getTime() + 6 * 60 * 60 * 1000).toISOString(),
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
});
