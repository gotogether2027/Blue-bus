import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { environment } from '../../../environments/environment';
import { bookingFixture } from '../../../testing/fixtures';
import { BookingsPageComponent } from './bookings.page';

describe('BookingsPageComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BookingsPageComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('renders booking reference, journey, status, and amount', () => {
    const fixture = TestBed.createComponent(BookingsPageComponent);
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings`).flush([
      bookingFixture({
        paymentStatus: 'SUCCEEDED',
        ticketNumber: 'T-9',
        ticketStatus: 'ACTIVE'
      })
    ]);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('BB-1001');
    expect(text).toContain('Visakhapatnam');
    expect(text).toContain('Hyderabad');
    expect(text).toContain('PENDING_PAYMENT');
    expect(text).toContain('Pay SUCCEEDED');
    expect(text).toContain('Ticket T-9');
  });

  it('handles null payment, ticket, and refund summary fields without placeholders from missing data', () => {
    const fixture = TestBed.createComponent(BookingsPageComponent);
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings`).flush([bookingFixture()]);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Payment not started');
    expect(text).not.toContain('Pay null');
    expect(text).not.toContain('Ticket null');
    expect(text).not.toContain('Refund null');
    expect(text).not.toContain('undefined');
  });
});
