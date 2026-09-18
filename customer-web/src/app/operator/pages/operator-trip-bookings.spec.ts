import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { Type, WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { environment } from '../../../environments/environment';
import { locationFixture } from '../../../testing/fixtures';
import {
  operatorBookingFixture,
  operatorBusFixture,
  operatorCancelledMultiPassengerBookingFixture,
  operatorPendingPaymentBookingFixture,
  operatorRouteFixture,
  operatorSharedSeatSegmentBookings,
  operatorTripFixture
} from '../../../testing/operator-fixtures';
import { routes } from '../../app.routes';
import { AuthService } from '../../core/auth/auth.service';
import { BOOKING_ITEM_STATUSES, BOOKING_STATUSES } from '../components/operator-booking-references';
import { OperatorBooking } from '../models/operator.models';
import { OperatorContextService } from '../services/operator-context.service';
import { OperatorBookingDetailPageComponent } from './operator-booking-detail/operator-booking-detail.page';
import { OperatorBookingsPageComponent } from './operator-bookings/operator-bookings.page';
import { OperatorTripDetailPageComponent } from './operator-trip-detail/operator-trip-detail.page';

describe('operator trip bookings', () => {
  const base = `${environment.apiBaseUrl}/operator`;
  const hyd = locationFixture('location-origin', 'Visakhapatnam', 'Andhra Pradesh');
  const vja = locationFixture('location-destination', 'Hyderabad', 'Telangana');
  const confirmed = operatorBookingFixture();
  const cancelled = operatorCancelledMultiPassengerBookingFixture();

  afterEach(() => {
    const http = TestBed.inject(HttpTestingController, null, { optional: true });
    http?.verify();
  });

  it('shows a loading state then the trip booking list', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[aria-label="Loading trip bookings"]')
    ).not.toBeNull();

    flushBookingsPage(setup.http, 'operator-1', [confirmed, cancelled]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Coastal Sleeper');
    expect(text).toContain('HYD-VJA');
    expect(text).toContain('BB-1001');
    expect(text).toContain('BB-2002');
    expect(text).toContain('CONFIRMED');
    expect(text).toContain('CANCELLED');
    expect(text).toContain('1 passenger');
    expect(text).toContain('Visakhapatnam');
    expect(text).toContain('View booking');
    expect(text).not.toContain('Email');
    expect(text).not.toContain('Payment provider');
  });

  it('requests bookings for the selected operator and trip', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    const bookings = setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings`);
    expect(bookings.request.method).toBe('GET');
    expect(bookings.request.params.keys().length).toBe(0);
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(operatorTripFixture());
    bookings.flush([confirmed]);
    setup.http.expectOne(`${base}/operator-1/buses/bus-1`).flush(operatorBusFixture());
    setup.http.expectOne(`${base}/operator-1/routes/route-1`).flush(operatorRouteFixture());
  });

  it('renders an empty booking list without fabricating counts', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', []);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('No bookings for this trip.');
    expect(text).toContain('Bookings');
    expect(fixture.componentInstance.bookings).toEqual([]);
    expect(fixture.componentInstance.passengerCount).toBe(0);
  });

  it('renders an empty passenger manifest from returned booking rows', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', []);
    fixture.detectChanges();

    clickNamedButton(fixture.nativeElement, 'Passenger manifest');
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('No passengers for this trip.');
  });

  it('renders an empty boarding view without inventing passengers', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', []);
    fixture.detectChanges();

    clickNamedButton(fixture.nativeElement, 'Boarding view');
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('No passengers for this trip.');
    expect(pageText(fixture.nativeElement)).toContain(
      'Ticket status is not available in the operator booking data.'
    );
  });

  it('filters loaded bookings by reference on the client', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed, cancelled]);
    fixture.detectChanges();

    fixture.componentInstance.searchQuery = 'BB-2002';
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('BB-2002');
    expect(text).not.toContain('BB-1001');
    expect(text).toContain('does not support server-side search');
  });

  it('searches boarding rows by passenger name on the client', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed, cancelled]);
    fixture.detectChanges();

    clickNamedButton(fixture.nativeElement, 'Boarding view');
    fixture.componentInstance.searchQuery = 'Asha Rao';
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Asha Rao');
    expect(text).not.toContain('Ravi Kumar');
  });

  it('filters loaded bookings by status on the client', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed, cancelled]);
    fixture.detectChanges();

    fixture.componentInstance.statusFilter = 'CANCELLED';
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('BB-2002');
    expect(text).not.toContain('BB-1001');
  });

  it('renders passenger and seat rows on the manifest', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed, cancelled]);
    fixture.detectChanges();

    clickNamedButton(fixture.nativeElement, 'Passenger manifest');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Asha Rao');
    expect(text).toContain('U1');
    expect(text).toContain('32');
    expect(text).toContain('FEMALE');
    expect(text).toContain('Ravi Kumar');
    expect(text).toContain('L2');
    expect(text).toContain('Meera Iyer');
    expect(text).toContain('L3');
    expect(text).toContain('BB-1001');
    expect(text).toContain('Visakhapatnam');
    expect(text).toContain('CONFIRMED');
    expect(text).not.toContain('Check-in');
    expect(text).not.toContain('Payment provider');
  });

  it('loads the boarding view from returned operator booking items', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed, cancelled]);
    fixture.detectChanges();

    clickNamedButton(fixture.nativeElement, 'Boarding view');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Asha Rao');
    expect(text).toContain('U1');
    expect(text).toContain('Visakhapatnam');
    expect(text).toContain('Hyderabad');
    expect(text).toContain('BB-1001');
    expect(text).toContain('CONFIRMED');
    expect(text).toContain('Confirmed booking');
    expect(text).toContain('Ticket status is not available in the operator booking data.');
    expect(text).not.toContain('CHECKED_IN');
    expect(text).not.toContain('BOARDED');
    expect(text).not.toContain('NO_SHOW');
    expect(text).not.toContain('Check-in');
  });

  it('distinguishes confirmed bookings from pending bookings without inventing boarding state', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [
      confirmed,
      operatorPendingPaymentBookingFixture()
    ]);
    fixture.detectChanges();

    clickNamedButton(fixture.nativeElement, 'Boarding view');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('CONFIRMED');
    expect(text).toContain('Confirmed booking');
    expect(text).toContain('PENDING_PAYMENT');
    expect(text).toContain('Not confirmed');
    expect(text).toContain('Kiran Shah');
    expect(text).not.toContain('Boarded');
  });

  it('filters boarding rows by origin segment on the client', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', operatorSharedSeatSegmentBookings());
    fixture.detectChanges();

    clickNamedButton(fixture.nativeElement, 'Boarding view');
    fixture.componentInstance.originFilter = '1:Hyderabad';
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Passenger A');
    expect(text).toContain('Hyderabad');
    expect(text).not.toContain('Passenger B');
  });

  it('filters passenger rows by booking item status on the client', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed, cancelled]);
    fixture.detectChanges();

    clickNamedButton(fixture.nativeElement, 'Passenger manifest');
    fixture.componentInstance.itemStatusFilter = 'ACTIVE';
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Asha Rao');
    expect(text).not.toContain('Ravi Kumar');
  });

  it('shows the same physical seat as separate journey segments', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', operatorSharedSeatSegmentBookings());
    fixture.detectChanges();

    clickNamedButton(fixture.nativeElement, 'Boarding view');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Seat R2');
    expect(text).toContain('Passenger A');
    expect(text).toContain('Passenger B');
    expect(text).toContain('Hyderabad');
    expect(text).toContain('Vijayawada');
    expect(text).toContain('Guntur');
    expect(text).toContain('BB-4001');
    expect(text).toContain('BB-4002');
    expect(text).toContain('Seq 1 → 2');
    expect(text).toContain('Seq 2 → 3');
    expect(text).toContain('same physical seat can appear more than once');
    expect(text).not.toContain('occupied by both');
  });

  it('keeps operator admin boarding access read-only', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' }, true);
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed]);
    fixture.detectChanges();

    clickNamedButton(fixture.nativeElement, 'Boarding view');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Asha Rao');
    expect(text).toContain('BB-1001');
    expect(text).not.toContain('Check-in');
    expect(text).not.toContain('Board passenger');
    expect(text).not.toContain('Cancel booking');
  });

  it('renders booking detail passengers, seats, and status without payment fields', async () => {
    const setup = await configure(OperatorBookingDetailPageComponent, {
      tripId: 'trip-1',
      bookingId: 'booking-1'
    });
    const fixture = TestBed.createComponent(OperatorBookingDetailPageComponent);
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[aria-label="Loading booking"]')
    ).not.toBeNull();

    const request = setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings/booking-1`);
    expect(request.request.method).toBe('GET');
    request.flush(confirmed);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('BB-1001');
    expect(text).toContain('Asha Rao');
    expect(text).toContain('Seat U1');
    expect(text).toContain('Visakhapatnam');
    expect(text).toContain('Hyderabad');
    expect(text).toContain('CONFIRMED');
    expect(text).toContain('ACTIVE');
    expect(text).not.toContain('Payment');
    expect(text).not.toContain('Issue refund');
    expect(text).not.toContain('Customer email');
  });

  it('handles a 401 booking list request', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(operatorTripFixture());
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings`).flush(
      {},
      { status: 401, statusText: 'Unauthorized' }
    );
    fixture.detectChanges();
    expect(setup.clearSession).toHaveBeenCalled();
    expect(pageText(fixture.nativeElement)).toContain('Your session has expired');
  });

  it('handles a 403 booking list request', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(operatorTripFixture());
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings`).flush(
      {},
      { status: 403, statusText: 'Forbidden' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('handles a 404 booking list request', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings`).flush([]);
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(
      {},
      { status: 404, statusText: 'Not Found' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Resource not found');
  });

  it('handles a 500 booking list request', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1`).flush(operatorTripFixture());
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings`).flush(
      {},
      { status: 500, statusText: 'Server Error' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Operator data is unavailable');
  });

  it('handles booking detail HTTP errors with the existing operator error service', async () => {
    const setup = await configure(OperatorBookingDetailPageComponent, {
      tripId: 'trip-1',
      bookingId: 'booking-1'
    });
    const fixture = TestBed.createComponent(OperatorBookingDetailPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings/booking-1`).flush(
      {},
      { status: 403, statusText: 'Forbidden' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");

    setup.selectedOperatorId.set('operator-1');
    fixture.componentInstance.load();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings/booking-1`).flush(
      {},
      { status: 401, statusText: 'Unauthorized' }
    );
    fixture.detectChanges();
    expect(setup.clearSession).toHaveBeenCalled();

    fixture.componentInstance.load();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings/booking-1`).flush(
      {},
      { status: 404, statusText: 'Not Found' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Resource not found');

    fixture.componentInstance.load();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings/booking-1`).flush(
      {},
      { status: 500, statusText: 'Server Error' }
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Operator data is unavailable');
  });

  it('keeps operator staff read-only on the trip booking list', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' }, false);
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('read-only booking access');
    expect(text).toContain('View booking');
    expect(text).toContain('Passenger manifest');
    expect(text).toContain('Boarding view');
    expect(text).not.toContain('Check-in');
    expect(text).not.toContain('Cancel booking');
    expect(text).not.toContain('Issue refund');
  });

  it('keeps operator staff read-only on booking detail', async () => {
    const setup = await configure(
      OperatorBookingDetailPageComponent,
      { tripId: 'trip-1', bookingId: 'booking-1' },
      false
    );
    const fixture = TestBed.createComponent(OperatorBookingDetailPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings/booking-1`).flush(confirmed);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Asha Rao');
    expect(text).toContain('read-only booking access');
    expect(text).not.toContain('Check-in');
    expect(text).not.toContain('Cancel booking');
    expect(text).not.toContain('Issue refund');
  });

  it('does not expose mutation controls for operator admins', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('View booking');
    expect(text).not.toContain('Check-in');
    expect(text).not.toContain('Board passenger');
    expect(text).not.toContain('Cancel booking');
    expect(text).not.toContain('Issue refund');
  });

  it('clears previous-operator bookings before loading the newly selected operator', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed]);
    expect(fixture.componentInstance.bookings[0].bookingReference).toBe('BB-1001');

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.bookings).toEqual([]);
    expect(fixture.componentInstance.trip).toBeNull();

    flushBookingsPage(
      setup.http,
      'operator-2',
      [
        operatorBookingFixture({
          bookingReference: 'BB-9009',
          trip: {
            ...confirmed.trip,
            operatorId: 'operator-2',
            busDisplayName: 'Inland Sleeper'
          }
        })
      ],
      operatorTripFixture({ operatorId: 'operator-2', busId: 'bus-2' }),
      operatorBusFixture({
        id: 'bus-2',
        operatorId: 'operator-2',
        displayName: 'Inland Sleeper'
      }),
      operatorRouteFixture({ operatorId: 'operator-2', code: 'VJA-HYD' })
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('BB-9009');
    expect(pageText(fixture.nativeElement)).not.toContain('BB-1001');
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Sleeper');
  });

  it('ignores a stale previous-operator booking response', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    const firstTrip = setup.http.expectOne(`${base}/operator-1/trips/trip-1`);
    const firstBookings = setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings`);

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.bookings).toEqual([]);

    firstTrip.flush(operatorTripFixture());
    firstBookings.flush([confirmed]);
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).not.toContain('BB-1001');

    flushBookingsPage(
      setup.http,
      'operator-2',
      [
        operatorBookingFixture({
          bookingReference: 'BB-9009',
          trip: { ...confirmed.trip, operatorId: 'operator-2' }
        })
      ],
      operatorTripFixture({ operatorId: 'operator-2', busId: 'bus-2' }),
      operatorBusFixture({
        id: 'bus-2',
        operatorId: 'operator-2',
        displayName: 'Inland Sleeper'
      }),
      operatorRouteFixture({ operatorId: 'operator-2', code: 'VJA-HYD' })
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('BB-9009');
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Sleeper');
  });

  it('rejects bookings whose trip belongs to another operator', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(
      setup.http,
      'operator-1',
      [confirmed],
      operatorTripFixture({ operatorId: 'operator-2' })
    );
    fixture.detectChanges();
    expect(fixture.componentInstance.bookings).toEqual([]);
    expect(pageText(fixture.nativeElement)).toContain(
      'These trip bookings do not belong to the selected operator.'
    );
  });

  it('rejects a booking payload for a different trip', async () => {
    const setup = await configure(OperatorBookingDetailPageComponent, {
      tripId: 'trip-1',
      bookingId: 'booking-1'
    });
    const fixture = TestBed.createComponent(OperatorBookingDetailPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings/booking-1`).flush(
      operatorBookingFixture({
        tripId: 'trip-9',
        trip: { ...confirmed.trip, tripId: 'trip-9' }
      })
    );
    fixture.detectChanges();
    expect(fixture.componentInstance.booking).toBeNull();
    expect(pageText(fixture.nativeElement)).toContain(
      'This booking does not belong to the selected operator trip.'
    );
  });

  it('clears previous booking detail on operator switch and ignores the stale response', async () => {
    const setup = await configure(OperatorBookingDetailPageComponent, {
      tripId: 'trip-1',
      bookingId: 'booking-1'
    });
    const fixture = TestBed.createComponent(OperatorBookingDetailPageComponent);
    fixture.detectChanges();
    const first = setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings/booking-1`);

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.booking).toBeNull();

    first.flush(confirmed);
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).not.toContain('BB-1001');

    setup.http.expectOne(`${base}/operator-2/trips/trip-1/bookings/booking-1`).flush(
      operatorBookingFixture({
        bookingReference: 'BB-9009',
        trip: { ...confirmed.trip, operatorId: 'operator-2' }
      })
    );
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('BB-9009');
    expect(pageText(fixture.nativeElement)).not.toContain('BB-1001');
  });

  it('links trip detail to the operator trip bookings page', async () => {
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
    expect(pageText(fixture.nativeElement)).toContain('Bookings');
    expect(hrefs.some((href) => href.includes('/operator/operator-1/trips/trip-1/bookings'))).toBeTrue();
    expect(hrefs.some((href) => href.includes('view=manifest'))).toBeTrue();
    expect(hrefs.some((href) => href.includes('view=boarding'))).toBeTrue();
    expect(hrefs.some((href) => href.includes('/operator/operator-1/trips/trip-1/inventory'))).toBeTrue();
    expect(pageText(fixture.nativeElement)).toContain('Passenger manifest');
    expect(pageText(fixture.nativeElement)).toContain('Boarding');
  });

  it('switches bookings, manifest, and boarding from query params without extra API calls', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed]);
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Trip bookings');

    setup.queryParams$.next(convertToParamMap({ view: 'manifest' }));
    fixture.detectChanges();
    expect(fixture.componentInstance.view).toBe('manifest');
    expect(pageText(fixture.nativeElement)).toContain('Passenger manifest');

    setup.queryParams$.next(convertToParamMap({ view: 'boarding' }));
    fixture.detectChanges();
    expect(fixture.componentInstance.view).toBe('boarding');
    expect(pageText(fixture.nativeElement)).toContain('Boarding view');
    setup.http.expectNone(() => true);
  });

  it('searches loaded bookings by passenger name on the client', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed, cancelled]);
    fixture.detectChanges();

    fixture.componentInstance.searchQuery = 'Asha Rao';
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('BB-1001');
    expect(text).not.toContain('BB-2002');
    expect(text).toContain('Showing 1 of 2 loaded bookings');
  });

  it('filters loaded bookings by booking item status on the client', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed, cancelled]);
    fixture.detectChanges();

    fixture.componentInstance.itemStatusFilter = 'ACTIVE';
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('BB-1001');
    expect(text).not.toContain('BB-2002');
  });

  it('clears client-side filters and restores the loaded booking count', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed, cancelled]);
    fixture.detectChanges();

    fixture.componentInstance.searchQuery = 'BB-2002';
    fixture.componentInstance.statusFilter = 'CANCELLED';
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).not.toContain('BB-1001');

    fixture.componentInstance.clearFilters();
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('BB-1001');
    expect(text).toContain('BB-2002');
    expect(text).toContain('Showing 2 of 2 loaded bookings');
    expect(fixture.componentInstance.searchQuery).toBe('');
    expect(fixture.componentInstance.statusFilter).toBe('ALL');
    expect(fixture.componentInstance.itemStatusFilter).toBe('ALL');
  });

  it('renders every operator booking status from the loaded list', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(
      setup.http,
      'operator-1',
      BOOKING_STATUSES.map((status) =>
        operatorBookingFixture({
          bookingId: `booking-${status}`,
          bookingReference: `REF-${status}`,
          status
        })
      )
    );
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    for (const status of BOOKING_STATUSES) {
      expect(text).toContain(status);
      expect(text).toContain(`REF-${status}`);
    }
    expect(text).not.toContain('BOARDED');
    expect(text).not.toContain('CHECKED_IN');
    expect(text).not.toContain('NO_SHOW');
  });

  it('renders every operator booking-item status on the manifest', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(
      setup.http,
      'operator-1',
      BOOKING_ITEM_STATUSES.map((status, index) =>
        operatorBookingFixture({
          bookingId: `booking-item-${status}`,
          bookingReference: `ITEM-${status}`,
          items: [
            {
              bookingItemId: `item-${status}`,
              passengerId: 'passenger-1',
              seatNumber: `S${index + 1}`,
              seatType: 'SLEEPER',
              originSequence: 1,
              destinationSequence: 2,
              status
            }
          ]
        })
      )
    );
    fixture.detectChanges();

    clickNamedButton(fixture.nativeElement, 'Passenger manifest');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    for (const status of BOOKING_ITEM_STATUSES) {
      expect(text).toContain(status);
      expect(text).toContain(`ITEM-${status}`);
    }
  });

  it('opens the boarding view from the existing bookings query parameter', async () => {
    const setup = await configure(
      OperatorBookingsPageComponent,
      { tripId: 'trip-1' },
      true,
      { view: 'boarding' }
    );
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    flushBookingsPage(setup.http, 'operator-1', [confirmed]);
    fixture.detectChanges();

    expect(fixture.componentInstance.view).toBe('boarding');
    expect(pageText(fixture.nativeElement)).toContain(
      'Ticket status is not available in the operator booking data.'
    );
    expect(pageText(fixture.nativeElement)).toContain('Asha Rao');
  });

  it('does not issue child API requests when the selected operator is unavailable', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    setup.selectedOperatorId.set(null);
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();

    setup.http.expectNone(() => true);
    expect(fixture.componentInstance.bookings).toEqual([]);
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('does not call customer ticket, payment, or refund APIs', async () => {
    const setup = await configure(OperatorBookingsPageComponent, { tripId: 'trip-1' });
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    const pending = setup.http.match(() => true);
    expect(
      pending.every((request) => request.request.url.startsWith(`${base}/operator-1/`))
    ).toBeTrue();
    expect(pending.every((request) => request.request.method === 'GET')).toBeTrue();
    expect(pending.some((request) => request.request.url.includes('/tickets'))).toBeFalse();
    expect(pending.some((request) => request.request.url.includes('/payments'))).toBeFalse();
    expect(pending.some((request) => request.request.url.includes('/refunds'))).toBeFalse();
    pending.forEach((request) => {
      if (request.request.url.endsWith('/trips/trip-1')) {
        request.flush(operatorTripFixture());
      } else if (request.request.url.endsWith('/bookings')) {
        request.flush([confirmed]);
      }
    });
    setup.http.expectOne(`${base}/operator-1/buses/bus-1`).flush(operatorBusFixture());
    setup.http.expectOne(`${base}/operator-1/routes/route-1`).flush(operatorRouteFixture());
    setup.http.expectNone((request) => request.url.includes('/ticket'));
    setup.http.expectNone((request) => request.url.includes('/payments'));
    setup.http.expectNone((request) => request.url.includes('/refunds'));
  });

  it('links booking detail back to bookings and the trip', async () => {
    const setup = await configure(OperatorBookingDetailPageComponent, {
      tripId: 'trip-1',
      bookingId: 'booking-1'
    });
    const fixture = TestBed.createComponent(OperatorBookingDetailPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/trips/trip-1/bookings/booking-1`).flush(confirmed);
    fixture.detectChanges();

    const hrefs = [...(fixture.nativeElement as HTMLElement).querySelectorAll('a')].map(
      (anchor) => anchor.getAttribute('href') ?? ''
    );
    const text = pageText(fixture.nativeElement);
    expect(hrefs).toContain('/operator/operator-1/trips/trip-1/bookings');
    expect(hrefs).toContain('/operator/operator-1/trips/trip-1');
    expect(text).toContain('INR');
    expect(text).toContain('Arrival');
    expect(text).toContain('Currency');
    expect(text).toContain('Origin sequence');
    expect(text).toContain('Destination sequence');
    expect(text).not.toContain('BOARDED');
  });

  it('does not issue booking-detail child API requests when the operator is unavailable', async () => {
    const setup = await configure(OperatorBookingDetailPageComponent, {
      tripId: 'trip-1',
      bookingId: 'booking-1'
    });
    setup.selectedOperatorId.set(null);
    const fixture = TestBed.createComponent(OperatorBookingDetailPageComponent);
    fixture.detectChanges();

    setup.http.expectNone(() => true);
    expect(fixture.componentInstance.booking).toBeNull();
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
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
    canManage = true,
    queryParams: Record<string, string> = {}
  ): Promise<{
    http: HttpTestingController;
    selectedOperatorId: WritableSignal<string | null>;
    clearSession: jasmine.Spy<() => void>;
    queryParams$: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  }> {
    const selectedOperatorId = signal<string | null>('operator-1');
    const canManageOperator = signal(canManage);
    const clearSession = jasmine.createSpy('clearSession');
    const queryParams$ = new BehaviorSubject(convertToParamMap(queryParams));

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
              queryParamMap: convertToParamMap(queryParams)
            },
            queryParamMap: queryParams$
          }
        }
      ]
    }).compileComponents();

    spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    return {
      http: TestBed.inject(HttpTestingController),
      selectedOperatorId,
      clearSession,
      queryParams$
    };
  }

  function flushBookingsPage(
    http: HttpTestingController,
    operatorId: string,
    bookings: OperatorBooking[],
    trip = operatorTripFixture({ operatorId }),
    bus = operatorBusFixture({ id: trip.busId, operatorId }),
    route = operatorRouteFixture({ id: trip.routeId, operatorId })
  ): void {
    http.expectOne(`${base}/${operatorId}/trips/${trip.id}`).flush(trip);
    http.expectOne(`${base}/${operatorId}/trips/${trip.id}/bookings`).flush(bookings);
    http.expectOne(`${base}/${operatorId}/buses/${trip.busId}`).flush(bus);
    http.expectOne(`${base}/${operatorId}/routes/${trip.routeId}`).flush(route);
  }

  function clickNamedButton(root: HTMLElement, label: string): void {
    const button = [...root.querySelectorAll('button')].find((candidate) =>
      (candidate.textContent ?? '').includes(label)
    );
    expect(button).toBeTruthy();
    button?.click();
  }

  function pageText(element: HTMLElement): string {
    return element.textContent ?? '';
  }
});
