import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { Type, WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRoute,
  Router,
  convertToParamMap,
  provideRouter
} from '@angular/router';
import { environment } from '../../../environments/environment';
import { locationFixture } from '../../../testing/fixtures';
import {
  operatorBusFixture,
  operatorRouteFixture,
  operatorTripFixture
} from '../../../testing/operator-fixtures';
import { routes } from '../../app.routes';
import { AuthService } from '../../core/auth/auth.service';
import { isoFromWallClock } from '../components/operator-trip-references';
import { OperatorContextService } from '../services/operator-context.service';
import { OperatorTripCreatePageComponent } from './operator-trip-create/operator-trip-create.page';
import { OperatorTripDetailPageComponent } from './operator-trip-detail/operator-trip-detail.page';
import { OperatorTripEditPageComponent } from './operator-trip-edit/operator-trip-edit.page';
import { OperatorTripsPageComponent } from './operator-trips/operator-trips.page';

describe('operator trip management', () => {
  const base = `${environment.apiBaseUrl}/operator`;
  const hyd = locationFixture('location-origin', 'Visakhapatnam', 'Andhra Pradesh');
  const vja = locationFixture('location-destination', 'Hyderabad', 'Telangana');

  afterEach(() => {
    const http = TestBed.inject(HttpTestingController, null, { optional: true });
    http?.verify();
  });

  it('converts wall-clock times in Asia/Kolkata to UTC instants', () => {
    expect(isoFromWallClock('2026-12-18T07:00', 'Asia/Kolkata')).toBe(
      '2026-12-18T01:30:00.000Z'
    );
  });

  it('renders an empty trip list without fabricating schedule data', async () => {
    const setup = await configure(OperatorTripsPageComponent);
    const fixture = TestBed.createComponent(OperatorTripsPageComponent);
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[aria-label="Loading trips"]')
    ).not.toBeNull();
    flushTripCollection(setup.http, 'operator-1', []);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('No trips');
    expect(text).toContain('Create the first trip');
  });

  it('loads the trip list with operator-safe bus and route summaries', async () => {
    const setup = await configure(OperatorTripsPageComponent);
    const fixture = TestBed.createComponent(OperatorTripsPageComponent);
    fixture.detectChanges();
    flushTripCollection(setup.http, 'operator-1', [operatorTripFixture()]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Coastal Sleeper');
    expect(text).toContain('HYD-VJA');
    expect(text).toContain('SCHEDULED');
    expect(text).toContain('View trip');
    expect(text).toContain('Create trip');
    expect(text).toContain('DRAFT');
    expect(text).toContain('ON_SALE');
  });

  it('filters the loaded trip list by search text', async () => {
    const setup = await configure(OperatorTripsPageComponent);
    const fixture = TestBed.createComponent(OperatorTripsPageComponent);
    fixture.detectChanges();
    flushTripCollection(setup.http, 'operator-1', [
      operatorTripFixture(),
      operatorTripFixture({
        id: 'trip-2',
        status: 'DRAFT',
        busId: 'bus-1',
        routeId: 'route-1'
      })
    ]);
    fixture.componentInstance.searchQuery = 'draft';
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('trip-2');
    expect(text).not.toContain('trip-1');
  });

  it('reloads trips with the backend status query parameter', async () => {
    const setup = await configure(OperatorTripsPageComponent);
    const fixture = TestBed.createComponent(OperatorTripsPageComponent);
    fixture.detectChanges();
    flushTripCollection(setup.http, 'operator-1', [operatorTripFixture()]);

    fixture.componentInstance.statusFilter = 'DRAFT';
    fixture.componentInstance.load();
    const trips = setup.http.expectOne(
      (request) =>
        request.url === `${base}/operator-1/trips` &&
        request.params.get('status') === 'DRAFT' &&
        request.params.get('serviceDate') === null
    );
    expect(trips.request.method).toBe('GET');
    trips.flush([operatorTripFixture({ id: 'trip-draft', status: 'DRAFT' })]);
    setup.http.expectOne(`${base}/operator-1/buses`).flush([operatorBusFixture()]);
    setup.http.expectOne(`${base}/operator-1/routes`).flush([operatorRouteFixture()]);
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain('trip-draft');
    expect(pageText(fixture.nativeElement)).not.toContain('trip-1');
  });

  it('shows an API error and retries the selected operator', async () => {
    const setup = await configure(OperatorTripsPageComponent);
    const fixture = TestBed.createComponent(OperatorTripsPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/buses`).flush([operatorBusFixture()]);
    setup.http.expectOne(`${base}/operator-1/routes`).flush([operatorRouteFixture()]);
    setup.http.expectOne(`${base}/operator-1/trips`).flush(
      {},
      { status: 500, statusText: 'Server Error' }
    );
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain('Operator data is unavailable');
    fixture.componentInstance.load();
    flushTripCollection(setup.http, 'operator-1', [operatorTripFixture()]);
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Coastal Sleeper');
  });

  it('hides list mutation controls from operator staff', async () => {
    const setup = await configure(OperatorTripsPageComponent, {}, false);
    const fixture = TestBed.createComponent(OperatorTripsPageComponent);
    fixture.detectChanges();
    flushTripCollection(setup.http, 'operator-1', [operatorTripFixture()]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('View trip');
    expect(text).toContain('read-only');
    expect(text).not.toContain('Create trip');
  });

  it('renders backend trip detail including stops and inventory snapshot', async () => {
    const setup = await configure(OperatorTripDetailPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripDetailPageComponent);
    fixture.detectChanges();
    flushTripDetail(setup.http, 'operator-1');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Coastal Sleeper');
    expect(text).toContain('HYD-VJA');
    expect(text).toContain('Asia/Kolkata');
    expect(text).toContain('SCHEDULED');
    expect(text).toContain('RTC Complex');
    expect(text).toContain('U1');
    expect(text).toContain('Edit commercial terms');
    expect(text).toContain('Cancel trip');
    expect(text).not.toContain('Schedule trip');
  });

  it('shows schedule only for DRAFT trips and hides cancel for completed trips', async () => {
    const setup = await configure(OperatorTripDetailPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripDetailPageComponent);
    fixture.detectChanges();
    flushTripDetail(setup.http, 'operator-1', operatorTripFixture({ status: 'DRAFT' }));
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Schedule trip');
    expect(pageText(fixture.nativeElement)).toContain('Cancel trip');

    setup.selectedOperatorId.set('operator-1');
    fixture.componentInstance.load();
    flushTripDetail(setup.http, 'operator-1', operatorTripFixture({ status: 'COMPLETED' }));
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).not.toContain('Schedule trip');
    expect(pageText(fixture.nativeElement)).not.toContain('Cancel trip');
    expect(pageText(fixture.nativeElement)).not.toContain('Edit commercial terms');
  });

  it('keeps operator staff read-only on trip detail', async () => {
    const setup = await configure(
      OperatorTripDetailPageComponent,
      { tripId: 'trip-1' },
      false
    );
    const fixture = TestBed.createComponent(OperatorTripDetailPageComponent);
    fixture.detectChanges();
    flushTripDetail(setup.http, 'operator-1');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('OPERATOR_STAFF membership provides read-only trip access');
    expect(text).toContain('Bookings');
    expect(text).not.toContain('Edit commercial terms');
    expect(text).not.toContain('Schedule trip');
    expect(text).not.toContain('Cancel trip');
  });

  it('shows a 404 when the trip is missing', async () => {
    const setup = await configure(OperatorTripDetailPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripDetailPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(
      {},
      { status: 404, statusText: 'Not Found' }
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.trip).toBeNull();
    expect(pageText(fixture.nativeElement)).toContain('Resource not found');
  });

  it('loads only eligible active buses and routes for trip creation', async () => {
    const setup = await configure(OperatorTripCreatePageComponent);
    const fixture = TestBed.createComponent(OperatorTripCreatePageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/buses`).flush([
      operatorBusFixture(),
      operatorBusFixture({
        id: 'bus-inactive',
        registrationNumber: 'AP00ZZ0000',
        displayName: 'Parked Bus',
        status: 'INACTIVE'
      }),
      operatorBusFixture({
        id: 'bus-other',
        operatorId: 'operator-2',
        registrationNumber: 'TS00AA1111',
        displayName: 'Other operator bus'
      })
    ]);
    const routes = setup.http.expectOne(
      (request) => request.url === `${base}/operator-1/routes`
    );
    expect(routes.request.params.get('status')).toBe('ACTIVE');
    routes.flush([
      operatorRouteFixture(),
      operatorRouteFixture({
        id: 'route-short',
        code: 'SHORT',
        name: 'Incomplete route',
        stops: [operatorRouteFixture().stops[0]]
      })
    ]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Coastal Sleeper');
    expect(text).toContain('HYD-VJA');
    expect(text).not.toContain('Parked Bus');
    expect(text).not.toContain('Other operator bus');
    expect(text).not.toContain('SHORT');
  });

  it('blocks create submission until required fields are present', async () => {
    const setup = await configure(OperatorTripCreatePageComponent);
    const fixture = TestBed.createComponent(OperatorTripCreatePageComponent);
    fixture.detectChanges();
    flushCreateReferences(setup.http, 'operator-1');
    fixture.componentInstance.submit();
    setup.http.expectNone(`${base}/operator-1/trips`);
    expect(fixture.componentInstance.form.invalid).toBeTrue();
  });

  it('creates a trip with the exact backend create contract', async () => {
    const setup = await configure(OperatorTripCreatePageComponent);
    const fixture = TestBed.createComponent(OperatorTripCreatePageComponent);
    fixture.detectChanges();
    flushCreateReferences(setup.http, 'operator-1');

    fixture.componentInstance.form.setValue({
      busId: 'bus-1',
      routeId: 'route-1',
      scheduledDepartureAt: '2026-12-18T07:00',
      scheduledArrivalAt: '2026-12-18T13:00',
      baseFare: 1299,
      bookingOpensAt: '2026-09-18T05:30',
      bookingClosesAt: '2026-12-18T06:00',
      timeZone: 'Asia/Kolkata'
    });
    fixture.componentInstance.submit();

    const request = setup.http.expectOne(`${base}/operator-1/trips`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      busId: 'bus-1',
      routeId: 'route-1',
      scheduledDepartureAt: '2026-12-18T01:30:00.000Z',
      scheduledArrivalAt: '2026-12-18T07:30:00.000Z',
      baseFare: 1299,
      bookingOpensAt: '2026-09-18T00:00:00.000Z',
      bookingClosesAt: '2026-12-18T00:30:00.000Z',
      timeZone: 'Asia/Kolkata'
    });
    request.flush(operatorTripFixture({ status: 'DRAFT' }));

    expect(setup.navigate).toHaveBeenCalledWith(
      ['/operator', 'operator-1', 'trips', 'trip-1'],
      { queryParams: { created: 'true' } }
    );
  });

  it('shows backend create validation and overlap conflicts without leaving the form', async () => {
    const setup = await configure(OperatorTripCreatePageComponent);
    const fixture = TestBed.createComponent(OperatorTripCreatePageComponent);
    fixture.detectChanges();
    flushCreateReferences(setup.http, 'operator-1');
    fillValidCreateForm(fixture.componentInstance);
    fixture.componentInstance.submit();
    setup.http.expectOne(`${base}/operator-1/trips`).flush(
      { message: 'Trip arrival must be after departure' },
      { status: 400, statusText: 'Bad Request' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Trip arrival must be after departure');
    expect((fixture.nativeElement as HTMLElement).querySelector('form')).not.toBeNull();

    fixture.componentInstance.submit();
    setup.http.expectOne(`${base}/operator-1/trips`).flush(
      { message: 'Bus already has an overlapping trip.' },
      { status: 409, statusText: 'Conflict' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Bus already has an overlapping trip.');
  });

  it('maps a create 403 to the operator permission error', async () => {
    const setup = await configure(OperatorTripCreatePageComponent);
    const fixture = TestBed.createComponent(OperatorTripCreatePageComponent);
    fixture.detectChanges();
    flushCreateReferences(setup.http, 'operator-1');
    fillValidCreateForm(fixture.componentInstance);
    fixture.componentInstance.submit();
    setup.http.expectOne(`${base}/operator-1/trips`).flush(
      {},
      { status: 403, statusText: 'Forbidden' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('patches only supported commercial fields', async () => {
    const setup = await configure(OperatorTripEditPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripEditPageComponent);
    fixture.detectChanges();
    flushTripEdit(setup.http, 'operator-1');
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[formControlName="busId"]')).toBeNull();
    expect(element.querySelector('[formControlName="routeId"]')).toBeNull();
    expect(element.querySelector('[formControlName="scheduledDepartureAt"]')).toBeNull();
    expect(pageText(element)).toContain('Coastal Sleeper');

    fixture.componentInstance.form.controls.baseFare.setValue(1499);
    fixture.componentInstance.submit();
    const request = setup.http.expectOne(`${base}/operator-1/trips/trip-1`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ baseFare: 1499 });
    request.flush(operatorTripFixture({ baseFare: 1499 }));

    expect(setup.navigate).toHaveBeenCalledWith(
      ['/operator', 'operator-1', 'trips', 'trip-1'],
      { queryParams: { updated: 'true' } }
    );
  });

  it('shows commercial-term 400, 409, and 403 responses', async () => {
    const setup = await configure(OperatorTripEditPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripEditPageComponent);
    fixture.detectChanges();
    flushTripEdit(setup.http, 'operator-1');

    fixture.componentInstance.form.controls.baseFare.setValue(1499);
    fixture.componentInstance.submit();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(
      { message: 'Trip commercial terms can only be updated while DRAFT or SCHEDULED' },
      { status: 400, statusText: 'Bad Request' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain(
      'Trip commercial terms can only be updated while DRAFT or SCHEDULED'
    );

    fixture.componentInstance.submit();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(
      { message: 'Trip booking window must close on or before departure' },
      { status: 409, statusText: 'Conflict' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain(
      'Trip booking window must close on or before departure'
    );

    fixture.componentInstance.submit();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(
      {},
      { status: 403, statusText: 'Forbidden' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('requires confirmation before scheduling and posts the exact schedule contract', async () => {
    const setup = await configure(OperatorTripDetailPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripDetailPageComponent);
    fixture.detectChanges();
    flushTripDetail(setup.http, 'operator-1', operatorTripFixture({ status: 'DRAFT' }));

    fixture.componentInstance.requestLifecycle('schedule');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="dialog"]')).not.toBeNull();
    expect(pageText(fixture.nativeElement)).toContain('lifecycle transition');
    setup.http.expectNone(`${base}/operator-1/trips/trip-1/schedule`);

    fixture.componentInstance.confirmLifecycle();
    const request = setup.http.expectOne(`${base}/operator-1/trips/trip-1/schedule`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush(operatorTripFixture({ status: 'SCHEDULED' }));
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain('Trip scheduled successfully.');
    expect(pageText(fixture.nativeElement)).toContain('SCHEDULED');
  });

  it('requires confirmation before cancellation and handles a 409', async () => {
    const setup = await configure(OperatorTripDetailPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripDetailPageComponent);
    fixture.detectChanges();
    flushTripDetail(setup.http, 'operator-1');

    fixture.componentInstance.requestLifecycle('cancel');
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('cannot be casually reversed');
    setup.http.expectNone(`${base}/operator-1/trips/trip-1/cancel`);

    fixture.componentInstance.confirmLifecycle();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/cancel`).flush(
      { message: 'Trip cannot be cancelled from status DEPARTED' },
      { status: 409, statusText: 'Conflict' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain(
      'Trip cannot be cancelled from status DEPARTED'
    );
  });

  it('cancels a trip after confirmation', async () => {
    const setup = await configure(OperatorTripDetailPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripDetailPageComponent);
    fixture.detectChanges();
    flushTripDetail(setup.http, 'operator-1');
    fixture.componentInstance.requestLifecycle('cancel');
    fixture.componentInstance.confirmLifecycle();
    const request = setup.http.expectOne(`${base}/operator-1/trips/trip-1/cancel`);
    expect(request.request.body).toBeNull();
    request.flush(operatorTripFixture({ status: 'CANCELLED' }));
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Trip cancelled successfully.');
    expect(pageText(fixture.nativeElement)).toContain('CANCELLED');
  });

  it('clears previous-operator trips before loading the newly selected operator', async () => {
    const setup = await configure(OperatorTripsPageComponent);
    const fixture = TestBed.createComponent(OperatorTripsPageComponent);
    fixture.detectChanges();
    flushTripCollection(setup.http, 'operator-1', [operatorTripFixture()]);
    expect(fixture.componentInstance.trips[0].id).toBe('trip-1');

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.trips).toEqual([]);

    flushTripCollection(setup.http, 'operator-2', [
      operatorTripFixture({
        id: 'trip-9',
        operatorId: 'operator-2',
        busId: 'bus-2'
      })
    ], [
      operatorBusFixture({
        id: 'bus-2',
        operatorId: 'operator-2',
        displayName: 'Inland Sleeper',
        registrationNumber: 'TS09XY9876'
      })
    ], [
      operatorRouteFixture({
        id: 'route-1',
        operatorId: 'operator-2',
        code: 'VJA-HYD',
        name: 'Return Service'
      })
    ]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Inland Sleeper');
    expect(text).not.toContain('Coastal Sleeper');
  });

  it('ignores a stale previous-operator trip detail response', async () => {
    const setup = await configure(OperatorTripDetailPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripDetailPageComponent);
    fixture.detectChanges();
    const firstTrip = setup.http.expectOne(`${base}/operator-1/trips/trip-1`);

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.trip).toBeNull();

    firstTrip.flush(operatorTripFixture());
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Sleeper');

    setup.http.expectOne(`${base}/operator-2/trips/trip-1`).flush(
      operatorTripFixture({
        operatorId: 'operator-2',
        busId: 'bus-2'
      })
    );
    setup.http
      .expectOne(`${base}/operator-2/buses/bus-2`)
      .flush(
        operatorBusFixture({
          id: 'bus-2',
          operatorId: 'operator-2',
          displayName: 'Inland Sleeper',
          registrationNumber: 'TS09XY9876'
        })
      );
    setup.http
      .expectOne(`${base}/operator-2/routes/route-1`)
      .flush(
        operatorRouteFixture({
          operatorId: 'operator-2',
          code: 'VJA-HYD',
          name: 'Return Service'
        })
      );
    setup.http.expectOne(`${environment.apiBaseUrl}/locations`).flush([hyd, vja]);
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain('Inland Sleeper');
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Sleeper');
  });

  it('handles a trip whose operator ID does not match the selected operator', async () => {
    const setup = await configure(OperatorTripDetailPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripDetailPageComponent);
    fixture.detectChanges();
    flushTripDetail(
      setup.http,
      'operator-1',
      operatorTripFixture({ operatorId: 'operator-2' })
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.trip).toBeNull();
    expect(pageText(fixture.nativeElement)).toContain(
      'This trip does not belong to the selected operator.'
    );
  });

  it('leaves customer booking and payment routes unchanged', () => {
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
  });

  async function configure(
    component: Type<unknown>,
    params: Record<string, string> = {},
    canManage = true
  ): Promise<{
    http: HttpTestingController;
    selectedOperatorId: WritableSignal<string | null>;
    clearSession: jasmine.Spy<() => void>;
    navigate: Router['navigate'];
  }> {
    const selectedOperatorId = signal<string | null>('operator-1');
    const canManageOperator = signal(canManage);
    const clearSession = jasmine.createSpy('clearSession');

    await TestBed.configureTestingModule({
      imports: [component],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: OperatorContextService,
          useValue: {
            selectedOperatorId: selectedOperatorId.asReadonly(),
            canManageOperator: canManageOperator.asReadonly()
          }
        },
        {
          provide: AuthService,
          useValue: { clearSession }
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap(params),
              queryParamMap: convertToParamMap({})
            }
          }
        }
      ]
    }).compileComponents();

    const http = TestBed.inject(HttpTestingController);
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate').and.resolveTo(true);
    return { http, selectedOperatorId, clearSession, navigate };
  }

  function flushTripCollection(
    http: HttpTestingController,
    operatorId: string,
    trips: ReturnType<typeof operatorTripFixture>[],
    buses = [operatorBusFixture({ operatorId })],
    routes = [operatorRouteFixture({ operatorId })]
  ): void {
    http.expectOne(`${base}/${operatorId}/trips`).flush(trips);
    http.expectOne(`${base}/${operatorId}/buses`).flush(buses);
    http.expectOne(`${base}/${operatorId}/routes`).flush(routes);
  }

  function flushCreateReferences(http: HttpTestingController, operatorId: string): void {
    http.expectOne(`${base}/${operatorId}/buses`).flush([operatorBusFixture({ operatorId })]);
    const routes = http.expectOne(
      (request) => request.url === `${base}/${operatorId}/routes`
    );
    expect(routes.request.params.get('status')).toBe('ACTIVE');
    routes.flush([operatorRouteFixture({ operatorId })]);
  }

  function flushTripDetail(
    http: HttpTestingController,
    operatorId: string,
    trip = operatorTripFixture({ operatorId })
  ): void {
    http.expectOne(`${base}/${operatorId}/trips/${trip.id}`).flush(trip);
    http
      .expectOne(`${base}/${operatorId}/buses/${trip.busId}`)
      .flush(operatorBusFixture({ id: trip.busId, operatorId }));
    http
      .expectOne(`${base}/${operatorId}/routes/${trip.routeId}`)
      .flush(operatorRouteFixture({ id: trip.routeId, operatorId }));
    http.expectOne(`${environment.apiBaseUrl}/locations`).flush([hyd, vja]);
  }

  function flushTripEdit(
    http: HttpTestingController,
    operatorId: string,
    trip = operatorTripFixture({ operatorId })
  ): void {
    http.expectOne(`${base}/${operatorId}/trips/${trip.id}`).flush(trip);
    http
      .expectOne(`${base}/${operatorId}/buses/${trip.busId}`)
      .flush(operatorBusFixture({ id: trip.busId, operatorId }));
    http
      .expectOne(`${base}/${operatorId}/routes/${trip.routeId}`)
      .flush(operatorRouteFixture({ id: trip.routeId, operatorId }));
  }

  function fillValidCreateForm(component: OperatorTripCreatePageComponent): void {
    component.form.setValue({
      busId: 'bus-1',
      routeId: 'route-1',
      scheduledDepartureAt: '2026-12-18T07:00',
      scheduledArrivalAt: '2026-12-18T13:00',
      baseFare: 1299,
      bookingOpensAt: '2026-09-18T05:30',
      bookingClosesAt: '2026-12-18T06:00',
      timeZone: 'Asia/Kolkata'
    });
  }

  function pageText(element: HTMLElement): string {
    return element.textContent ?? '';
  }
});
