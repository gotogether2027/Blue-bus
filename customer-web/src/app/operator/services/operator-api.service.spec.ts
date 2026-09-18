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
  operatorMembershipFixture,
  operatorProfileFixture,
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
