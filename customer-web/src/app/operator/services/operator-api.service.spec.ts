import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import {
  operatorBookingFixture,
  operatorBusFixture,
  operatorBusTypeFixture,
  operatorMembershipFixture,
  operatorProfileFixture,
  operatorRouteFixture,
  operatorRouteStopFixture,
  operatorSeatLayoutFixture,
  operatorTripFixture
} from '../../../testing/operator-fixtures';
import { OperatorApiService } from './operator-api.service';

describe('OperatorApiService', () => {
  let service: OperatorApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(OperatorApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads operator memberships from the authenticated identity namespace', () => {
    const memberships = [operatorMembershipFixture()];
    service.listMemberships().subscribe((result) => expect(result).toEqual(memberships));

    const request = http.expectOne(`${environment.apiBaseUrl}/auth/operator-memberships`);
    expect(request.request.method).toBe('GET');
    request.flush(memberships);
  });

  it('loads the operator profile and buses from operator-scoped URLs', () => {
    service.getOperator('operator-1').subscribe((result) => {
      expect(result.displayName).toBe('Coastal Travels');
    });
    http
      .expectOne(`${environment.apiBaseUrl}/operator/operator-1`)
      .flush(operatorProfileFixture());

    service.listBuses('operator-1').subscribe((result) => {
      expect(result[0].registrationNumber).toBe('AP31AB1234');
    });
    http
      .expectOne(`${environment.apiBaseUrl}/operator/operator-1/buses`)
      .flush([operatorBusFixture()]);
  });

  it('loads operator-safe active bus types and published seat layouts', () => {
    service.listActiveBusTypes('operator-1').subscribe((result) => {
      expect(result).toEqual([operatorBusTypeFixture()]);
    });
    const typesRequest = http.expectOne(
      `${environment.apiBaseUrl}/operator/operator-1/bus-types`
    );
    expect(typesRequest.request.method).toBe('GET');
    typesRequest.flush([operatorBusTypeFixture()]);

    service.listPublishedSeatLayouts('operator-1').subscribe((result) => {
      expect(result).toEqual([operatorSeatLayoutFixture()]);
    });
    const layoutsRequest = http.expectOne(
      (candidate) =>
        candidate.url ===
        `${environment.apiBaseUrl}/operator/operator-1/seat-layouts`
    );
    expect(layoutsRequest.request.method).toBe('GET');
    expect(layoutsRequest.request.params.get('status')).toBe('PUBLISHED');
    layoutsRequest.flush([operatorSeatLayoutFixture()]);
  });

  it('uses the exact operator bus mutation contracts', () => {
    const createRequest = {
      busTypeId: 'bus-type-1',
      seatLayoutId: 'layout-1',
      registrationNumber: 'AP31AB1234',
      displayName: 'Coastal Sleeper'
    };
    service.createBus('operator-1', createRequest).subscribe();
    const create = http.expectOne(`${environment.apiBaseUrl}/operator/operator-1/buses`);
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual(createRequest);
    create.flush(operatorBusFixture());

    service
      .updateBus('operator-1', 'bus-1', { displayName: 'Night Rider' })
      .subscribe();
    const update = http.expectOne(
      `${environment.apiBaseUrl}/operator/operator-1/buses/bus-1`
    );
    expect(update.request.method).toBe('PATCH');
    expect(update.request.body).toEqual({ displayName: 'Night Rider' });
    update.flush(operatorBusFixture({ displayName: 'Night Rider' }));

    const lifecycleCases = [
      ['activate', () => service.activateBus('operator-1', 'bus-1')],
      ['deactivate', () => service.deactivateBus('operator-1', 'bus-1')],
      ['maintenance', () => service.markBusMaintenance('operator-1', 'bus-1')]
    ] as const;
    for (const [action, invoke] of lifecycleCases) {
      invoke().subscribe();
      const lifecycle = http.expectOne(
        `${environment.apiBaseUrl}/operator/operator-1/buses/bus-1/${action}`
      );
      expect(lifecycle.request.method).toBe('POST');
      expect(lifecycle.request.body).toBeNull();
      lifecycle.flush(operatorBusFixture());
    }
  });

  it('uses the exact operator route mutation contracts', () => {
    const createRequest = {
      code: 'HYD-VJA',
      name: 'Hyderabad to Vijayawada',
      sourceLocationId: 'location-hyd',
      destinationLocationId: 'location-vja'
    };
    service.createRoute('operator-1', createRequest).subscribe();
    const create = http.expectOne(`${environment.apiBaseUrl}/operator/operator-1/routes`);
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual(createRequest);
    create.flush(operatorRouteFixture());

    service.updateRoute('operator-1', 'route-1', { name: 'Night Service' }).subscribe();
    const update = http.expectOne(
      `${environment.apiBaseUrl}/operator/operator-1/routes/route-1`
    );
    expect(update.request.method).toBe('PATCH');
    expect(update.request.body).toEqual({ name: 'Night Service' });
    update.flush(operatorRouteFixture({ name: 'Night Service' }));

    service
      .addRouteStop('operator-1', 'route-1', {
        locationId: 'location-hyd',
        sequenceNumber: 1,
        stopKind: 'SOURCE'
      })
      .subscribe();
    const addStop = http.expectOne(
      `${environment.apiBaseUrl}/operator/operator-1/routes/route-1/stops`
    );
    expect(addStop.request.method).toBe('POST');
    addStop.flush(operatorRouteStopFixture());

    service.activateRoute('operator-1', 'route-1').subscribe();
    const activate = http.expectOne(
      `${environment.apiBaseUrl}/operator/operator-1/routes/route-1/activate`
    );
    expect(activate.request.method).toBe('POST');
    expect(activate.request.body).toBeNull();
    activate.flush(operatorRouteFixture());
  });

  it('forwards only supported trip filters', () => {
    service
      .listTrips('operator-1', { serviceDate: '2026-12-18', status: 'SCHEDULED' })
      .subscribe((result) => expect(result).toEqual([operatorTripFixture()]));

    const request = http.expectOne(
      (candidate) =>
        candidate.url === `${environment.apiBaseUrl}/operator/operator-1/trips`
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('serviceDate')).toBe('2026-12-18');
    expect(request.request.params.get('status')).toBe('SCHEDULED');
    request.flush([operatorTripFixture()]);
  });

  it('loads trip bookings and booking detail from the trip namespace', () => {
    service.listTripBookings('operator-1', 'trip-1').subscribe((result) => {
      expect(result[0].bookingReference).toBe('BB-1001');
    });
    http
      .expectOne(
        `${environment.apiBaseUrl}/operator/operator-1/trips/trip-1/bookings`
      )
      .flush([operatorBookingFixture()]);

    service.getTripBooking('operator-1', 'trip-1', 'booking-1').subscribe((result) => {
      expect(result.passengers[0].fullName).toBe('Asha Rao');
    });
    http
      .expectOne(
        `${environment.apiBaseUrl}/operator/operator-1/trips/trip-1/bookings/booking-1`
      )
      .flush(operatorBookingFixture());
  });
});
