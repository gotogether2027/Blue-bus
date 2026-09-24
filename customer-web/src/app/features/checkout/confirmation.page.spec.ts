import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { convertToParamMap } from '@angular/router';
import { ActivatedRoute } from '@angular/router';
import { environment } from '../../../environments/environment';
import { bookingFixture, ticketFixture } from '../../../testing/fixtures';
import { ConfirmationPageComponent } from './confirmation.page';

describe('ConfirmationPageComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ConfirmationPageComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ bookingId: 'booking-1' }) } }
        }
      ]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('renders booking confirmation details', () => {
    const fixture = TestBed.createComponent(ConfirmationPageComponent);
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1`).flush(
      bookingFixture({ status: 'PENDING_PAYMENT', ticketId: null, ticketStatus: null })
    );
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('BB-1001');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No ticket has been issued yet.');
    fixture.destroy();
  });

  it('shows ticket-not-yet-issued copy when the ticket GET returns 404 for a confirmed booking', () => {
    const fixture = TestBed.createComponent(ConfirmationPageComponent);
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1`).flush(
      bookingFixture({ status: 'CONFIRMED', ticketId: null, ticketStatus: null, paymentStatus: 'SUCCEEDED' })
    );
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/ticket`).flush(
      { message: 'not found' },
      { status: 404, statusText: 'Not Found' }
    );
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Ticket is being prepared');
    fixture.destroy();
  });

  it('links to the digital ticket page after a ticket is issued', () => {
    const fixture = TestBed.createComponent(ConfirmationPageComponent);
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1`).flush(
      bookingFixture({
        status: 'CONFIRMED',
        ticketId: 'ticket-1',
        ticketNumber: 'T-1001',
        ticketStatus: 'ACTIVE',
        paymentStatus: 'SUCCEEDED'
      })
    );
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/ticket`).flush(ticketFixture());
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('View digital ticket');
    expect(text).toContain('T-1001');
    fixture.destroy();
  });
});
