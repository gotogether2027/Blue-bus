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
  operatorTripFixture,
  operatorTripSeatInventoryFixture
} from '../../../testing/operator-fixtures';
import { routes } from '../../app.routes';
import { AuthService } from '../../core/auth/auth.service';
import { OperatorContextService } from '../services/operator-context.service';
import { OperatorTripDetailPageComponent } from './operator-trip-detail/operator-trip-detail.page';
import { OperatorTripInventoryPageComponent } from './operator-trip-inventory/operator-trip-inventory.page';

describe('operator trip inventory', () => {
  const base = `${environment.apiBaseUrl}/operator`;
  const hyd = locationFixture('location-origin', 'Visakhapatnam', 'Andhra Pradesh');
  const vja = locationFixture('location-destination', 'Hyderabad', 'Telangana');
  const availableSeat = operatorTripSeatInventoryFixture();
  const blockedSeat = operatorTripSeatInventoryFixture({
    id: 'inventory-2',
    layoutSeatId: 'layout-seat-2',
    seatNumber: 'L1',
    deckNumber: 2,
    rowNumber: 1,
    columnNumber: 1,
    physicalStatus: 'BLOCKED',
    blockReason: 'Broken recliner'
  });

  afterEach(() => {
    const http = TestBed.inject(HttpTestingController, null, { optional: true });
    http?.verify();
  });

  it('shows a loading state then the physical inventory', async () => {
    const setup = await configure(OperatorTripInventoryPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[aria-label="Loading trip inventory"]')
    ).not.toBeNull();

    flushInventoryPage(setup.http, 'operator-1', [availableSeat, blockedSeat]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Coastal Sleeper');
    expect(text).toContain('HYD-VJA');
    expect(text).toContain('U1');
    expect(text).toContain('L1');
    expect(text).toContain('AVAILABLE');
    expect(text).toContain('BLOCKED');
    expect(text).toContain('Broken recliner');
    expect(text).toContain('Deck 1');
    expect(text).toContain('Deck 2');
    expect(text).toContain('Physical seat status and passenger allocation are separate');
    expect(text).not.toContain('UNSOLD');
    expect(text).not.toContain('BOOKED');
  });

  it('renders an empty inventory without fabricating seats', async () => {
    const setup = await configure(OperatorTripInventoryPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    flushInventoryPage(setup.http, 'operator-1', []);
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('No seat inventory');
  });

  it('requests inventory for the selected operator and trip', async () => {
    const setup = await configure(OperatorTripInventoryPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    const inventory = setup.http.expectOne(`${base}/operator-1/trips/trip-1/inventory`);
    expect(inventory.request.method).toBe('GET');
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(operatorTripFixture());
    inventory.flush([availableSeat]);
    setup.http.expectOne(`${base}/operator-1/buses/bus-1`).flush(operatorBusFixture());
    setup.http.expectOne(`${base}/operator-1/routes/route-1`).flush(operatorRouteFixture());
  });

  it('handles a 401 inventory request', async () => {
    const setup = await configure(OperatorTripInventoryPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(operatorTripFixture());
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/inventory`).flush(
      {},
      { status: 401, statusText: 'Unauthorized' }
    );
    fixture.detectChanges();
    expect(setup.clearSession).toHaveBeenCalled();
    expect(pageText(fixture.nativeElement)).toContain('Your session has expired');
  });

  it('handles a 403 inventory request', async () => {
    const setup = await configure(OperatorTripInventoryPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(operatorTripFixture());
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/inventory`).flush(
      {},
      { status: 403, statusText: 'Forbidden' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('handles a 404 inventory request', async () => {
    const setup = await configure(OperatorTripInventoryPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/inventory`).flush([]);
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(
      {},
      { status: 404, statusText: 'Not Found' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Resource not found');
  });

  it('handles a 500 inventory request', async () => {
    const setup = await configure(OperatorTripInventoryPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(operatorTripFixture());
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/inventory`).flush(
      {},
      { status: 500, statusText: 'Server Error' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Operator data is unavailable');
  });

  it('keeps operator staff read-only', async () => {
    const setup = await configure(
      OperatorTripInventoryPageComponent,
      { tripId: 'trip-1' },
      false
    );
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    flushInventoryPage(setup.http, 'operator-1', [availableSeat, blockedSeat]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('read-only inventory access');
    expect(text).not.toContain('Block seat');
    expect(text).not.toContain('Unblock seat');
  });

  it('hides mutation controls when the trip is no longer mutable', async () => {
    const setup = await configure(OperatorTripInventoryPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    flushInventoryPage(
      setup.http,
      'operator-1',
      [availableSeat],
      operatorTripFixture({ status: 'COMPLETED' })
    );
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('DRAFT, SCHEDULED, or ON_SALE');
    expect(text).toContain('COMPLETED');
    expect(text).not.toContain('Block seat');
    expect(text).not.toContain('Unblock seat');
  });

  it('requires a block reason and posts the exact block contract', async () => {
    const setup = await configure(OperatorTripInventoryPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    flushInventoryPage(setup.http, 'operator-1', [availableSeat]);

    fixture.componentInstance.requestBlock(availableSeat);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="dialog"]')).not.toBeNull();
    fixture.componentInstance.confirmPending();
    setup.http.expectNone(
      `${base}/operator-1/trips/trip-1/inventory/inventory-1/block`
    );

    fixture.componentInstance.blockReason = '  Broken recliner  ';
    fixture.componentInstance.confirmPending();
    const request = setup.http.expectOne(
      `${base}/operator-1/trips/trip-1/inventory/inventory-1/block`
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ reason: 'Broken recliner' });
    request.flush(
      operatorTripSeatInventoryFixture({
        physicalStatus: 'BLOCKED',
        blockReason: 'Broken recliner'
      })
    );
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain('Seat U1 is physically blocked.');
    expect(pageText(fixture.nativeElement)).toContain('Broken recliner');
  });

  it('shows backend 400 and 409 block errors', async () => {
    const setup = await configure(OperatorTripInventoryPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    flushInventoryPage(setup.http, 'operator-1', [availableSeat]);

    fixture.componentInstance.requestBlock(availableSeat);
    fixture.componentInstance.blockReason = 'late';
    fixture.componentInstance.confirmPending();
    setup.http
      .expectOne(`${base}/operator-1/trips/trip-1/inventory/inventory-1/block`)
      .flush(
        { message: 'Trip seat inventory cannot be changed while the trip is COMPLETED.' },
        { status: 400, statusText: 'Bad Request' }
      );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain(
      'Trip seat inventory cannot be changed while the trip is COMPLETED.'
    );

    fixture.componentInstance.requestBlock(availableSeat);
    fixture.componentInstance.blockReason = 'late';
    fixture.componentInstance.confirmPending();
    setup.http
      .expectOne(`${base}/operator-1/trips/trip-1/inventory/inventory-1/block`)
      .flush(
        { message: 'Seat is already changing.' },
        { status: 409, statusText: 'Conflict' }
      );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Seat is already changing.');
  });

  it('unblocks a seat with the exact unblock contract', async () => {
    const setup = await configure(OperatorTripInventoryPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    flushInventoryPage(setup.http, 'operator-1', [blockedSeat]);

    fixture.componentInstance.requestUnblock(blockedSeat);
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Existing holds and bookings are not released');
    fixture.componentInstance.confirmPending();
    const request = setup.http.expectOne(
      `${base}/operator-1/trips/trip-1/inventory/inventory-2/unblock`
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush(
      operatorTripSeatInventoryFixture({
        id: 'inventory-2',
        seatNumber: 'L1',
        physicalStatus: 'AVAILABLE',
        blockReason: null
      })
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Seat L1 is physically available again.');
  });

  it('clears previous-operator inventory before loading the newly selected operator', async () => {
    const setup = await configure(OperatorTripInventoryPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    flushInventoryPage(setup.http, 'operator-1', [availableSeat]);
    expect(fixture.componentInstance.inventory[0].seatNumber).toBe('U1');

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.inventory).toEqual([]);
    expect(fixture.componentInstance.trip).toBeNull();

    flushInventoryPage(
      setup.http,
      'operator-2',
      [operatorTripSeatInventoryFixture({ tripId: 'trip-1', seatNumber: 'Z9' })],
      operatorTripFixture({ operatorId: 'operator-2', busId: 'bus-2' }),
      operatorBusFixture({
        id: 'bus-2',
        operatorId: 'operator-2',
        displayName: 'Inland Sleeper'
      }),
      operatorRouteFixture({ operatorId: 'operator-2', code: 'VJA-HYD' })
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Z9');
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Sleeper');
  });

  it('ignores a stale previous-operator inventory response', async () => {
    const setup = await configure(OperatorTripInventoryPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    const firstTrip = setup.http.expectOne(`${base}/operator-1/trips/trip-1`);
    const firstInventory = setup.http.expectOne(`${base}/operator-1/trips/trip-1/inventory`);

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.inventory).toEqual([]);

    firstTrip.flush(operatorTripFixture());
    firstInventory.flush([availableSeat]);
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).not.toContain('U1');

    setup.http
      .expectOne(`${base}/operator-2/trips/trip-1`)
      .flush(operatorTripFixture({ operatorId: 'operator-2', busId: 'bus-2' }));
    setup.http
      .expectOne(`${base}/operator-2/trips/trip-1/inventory`)
      .flush([operatorTripSeatInventoryFixture({ tripId: 'trip-1', seatNumber: 'Z9' })]);
    setup.http
      .expectOne(`${base}/operator-2/buses/bus-2`)
      .flush(
        operatorBusFixture({
          id: 'bus-2',
          operatorId: 'operator-2',
          displayName: 'Inland Sleeper'
        })
      );
    setup.http
      .expectOne(`${base}/operator-2/routes/route-1`)
      .flush(operatorRouteFixture({ operatorId: 'operator-2', code: 'VJA-HYD' }));
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Z9');
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Sleeper');
  });

  it('rejects inventory whose trip belongs to another operator', async () => {
    const setup = await configure(OperatorTripInventoryPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripInventoryPageComponent);
    fixture.detectChanges();
    setup.http
      .expectOne(`${base}/operator-1/trips/trip-1`)
      .flush(operatorTripFixture({ operatorId: 'operator-2' }));
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/inventory`).flush([availableSeat]);
    setup.http.expectOne(`${base}/operator-1/buses/bus-1`).flush(operatorBusFixture());
    setup.http.expectOne(`${base}/operator-1/routes/route-1`).flush(operatorRouteFixture());
    fixture.detectChanges();
    expect(fixture.componentInstance.inventory).toEqual([]);
    expect(pageText(fixture.nativeElement)).toContain(
      'This trip inventory does not belong to the selected operator.'
    );
  });

  it('links trip detail to the operator inventory page', async () => {
    const setup = await configure(OperatorTripDetailPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorTripDetailPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(operatorTripFixture());
    setup.http.expectOne(`${base}/operator-1/buses/bus-1`).flush(operatorBusFixture());
    setup.http.expectOne(`${base}/operator-1/routes/route-1`).flush(operatorRouteFixture());
    setup.http.expectOne(`${environment.apiBaseUrl}/locations`).flush([hyd, vja]);
    fixture.detectChanges();

    const hrefs = [...(fixture.nativeElement as HTMLElement).querySelectorAll('a')].map(
      (anchor) => anchor.getAttribute('href') ?? ''
    );
    expect(pageText(fixture.nativeElement)).toContain('Inventory');
    expect(pageText(fixture.nativeElement)).toContain('Open inventory');
    expect(hrefs.some((href) => href.includes('/operator/operator-1/trips/trip-1/inventory'))).toBeTrue();
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

  function flushInventoryPage(
    http: HttpTestingController,
    operatorId: string,
    seats: ReturnType<typeof operatorTripSeatInventoryFixture>[],
    trip = operatorTripFixture({ operatorId }),
    bus = operatorBusFixture({ id: trip.busId, operatorId }),
    route = operatorRouteFixture({ id: trip.routeId, operatorId })
  ): void {
    http.expectOne(`${base}/${operatorId}/trips/${trip.id}`).flush(trip);
    http.expectOne(`${base}/${operatorId}/trips/${trip.id}/inventory`).flush(seats);
    http.expectOne(`${base}/${operatorId}/buses/${trip.busId}`).flush(bus);
    http.expectOne(`${base}/${operatorId}/routes/${trip.routeId}`).flush(route);
  }

  function pageText(element: HTMLElement): string {
    return element.textContent ?? '';
  }
});
