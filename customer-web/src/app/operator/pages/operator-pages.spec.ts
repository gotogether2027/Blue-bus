import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { environment } from '../../../environments/environment';
import {
  operatorBookingFixture,
  operatorBusFixture,
  operatorTripFixture
} from '../../../testing/operator-fixtures';
import { AuthService } from '../../core/auth/auth.service';
import { OperatorContextService } from '../services/operator-context.service';
import { OperatorBookingDetailPageComponent } from './operator-booking-detail/operator-booking-detail.page';
import { OperatorBookingsPageComponent } from './operator-bookings/operator-bookings.page';
import { OperatorBusDetailPageComponent } from './operator-bus-detail/operator-bus-detail.page';
import { OperatorBusesPageComponent } from './operator-buses/operator-buses.page';
import { OperatorTripsPageComponent } from './operator-trips/operator-trips.page';

describe('operator resource pages', () => {
  let http: HttpTestingController;
  const selectedOperatorId = signal<string | null>('operator-1');
  const base = `${environment.apiBaseUrl}/operator/operator-1`;

  beforeEach(async () => {
    selectedOperatorId.set('operator-1');
    await TestBed.configureTestingModule({
      imports: [
        OperatorBusesPageComponent,
        OperatorBusDetailPageComponent,
        OperatorTripsPageComponent,
        OperatorBookingsPageComponent,
        OperatorBookingDetailPageComponent
      ],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: OperatorContextService,
          useValue: { selectedOperatorId: selectedOperatorId.asReadonly() }
        },
        {
          provide: AuthService,
          useValue: { clearSession: jasmine.createSpy('clearSession') }
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({
                busId: 'bus-1',
                tripId: 'trip-1',
                bookingId: 'booking-1'
              })
            }
          }
        }
      ]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads and renders buses', () => {
    const fixture = TestBed.createComponent(OperatorBusesPageComponent);
    fixture.detectChanges();
    http.expectOne(`${base}/buses`).flush([operatorBusFixture()]);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Coastal Sleeper');
    expect(text).toContain('AP31AB1234');
    expect(text).toContain('layout-1');
  });

  it('loads and renders trips with bus data', () => {
    const fixture = TestBed.createComponent(OperatorTripsPageComponent);
    fixture.detectChanges();
    http.expectOne(`${base}/trips`).flush([operatorTripFixture()]);
    http.expectOne(`${base}/buses`).flush([operatorBusFixture()]);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Coastal Sleeper');
    expect(text).toContain('route-1');
    expect(text).toContain('SCHEDULED');
  });

  it('loads and renders trip bookings without customer contact or payment data', () => {
    const fixture = TestBed.createComponent(OperatorBookingsPageComponent);
    fixture.detectChanges();
    http.expectOne(`${base}/trips/trip-1/bookings`).flush([operatorBookingFixture()]);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('BB-1001');
    expect(text).toContain('Visakhapatnam');
    expect(text).toContain('1 passenger');
    expect(text).not.toContain('Email');
    expect(text).not.toContain('Payment provider');
  });

  it('shows the required operator message for a backend 403', () => {
    const fixture = TestBed.createComponent(OperatorBusesPageComponent);
    fixture.detectChanges();
    http
      .expectOne(`${base}/buses`)
      .flush({}, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain("You don't have access to this operator.");
  });

  it('shows a resource-not-found state for a backend 404', () => {
    const fixture = TestBed.createComponent(OperatorBusDetailPageComponent);
    fixture.detectChanges();
    http
      .expectOne(`${base}/buses/bus-1`)
      .flush({}, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Resource not found');
    expect(text).toContain('requested operator resource was not found');
  });

  it('renders only the operator booking manifest fields returned by the DTO', () => {
    const fixture = TestBed.createComponent(OperatorBookingDetailPageComponent);
    fixture.detectChanges();
    http
      .expectOne(`${base}/trips/trip-1/bookings/booking-1`)
      .flush(operatorBookingFixture());
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Asha Rao');
    expect(text).toContain('Seat U1');
    expect(text).toContain('VSKP-HYD');
    expect(text).not.toContain('Payment');
    expect(text).not.toContain('Refund');
    expect(text).not.toContain('Customer email');
  });
});
