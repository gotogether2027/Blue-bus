import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { environment } from '../../../environments/environment';
import { ticketFixture } from '../../../testing/fixtures';
import { TicketsService } from './tickets.service';

describe('TicketsService', () => {
  let http: HttpTestingController;
  let service: TicketsService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(TicketsService);
  });

  afterEach(() => http.verify());

  it('reads the ticket for a booking', () => {
    let number = '';
    service.getByBooking('booking-1').subscribe((ticket) => (number = ticket.ticketNumber));
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/ticket`).flush(ticketFixture());
    expect(number).toBe('T-1001');
  });

  it('downloads the official PDF for a booking', () => {
    let body: Blob | null = null;
    service.downloadPdf('booking-1').subscribe((blob) => (body = blob));
    const req = http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/ticket/pdf`);
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['%PDF-1.4'], { type: 'application/pdf' }));
    expect(body).toBeTruthy();
  });
});
