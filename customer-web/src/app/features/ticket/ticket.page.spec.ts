import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ticketFixture } from '../../../testing/fixtures';
import { TicketPageComponent } from './ticket.page';

describe('TicketPageComponent', () => {
  let http: HttpTestingController;
  let paramMap$: BehaviorSubject<ParamMap>;

  beforeEach(async () => {
    paramMap$ = new BehaviorSubject(convertToParamMap({ bookingId: 'booking-1' }));
    await TestBed.configureTestingModule({
      imports: [TicketPageComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: paramMap$ }
        }
      ]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('renders digital ticket details, heading, and QR image', async () => {
    const fixture = TestBed.createComponent(TicketPageComponent);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Loading your ticket...');

    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/ticket`).flush(ticketFixture());
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    const text = root.textContent ?? '';
    expect(root.querySelector('h1')?.textContent).toContain('Digital Ticket');
    expect(text).toContain('T-1001');
    expect(text).toContain('BB-1001');
    expect(text).toContain('Visakhapatnam');
    expect(text).toContain('Hyderabad');
    expect(text).toContain('Asha Rao');
    expect(text).toContain('Scan to verify ticket');
    expect(text).toContain('Print ticket');
    expect(text).toContain('Download PDF');

    const qr = root.querySelector('img');
    expect(qr).withContext('QR image').not.toBeNull();
    expect(qr?.getAttribute('src') ?? '').toMatch(/^data:image\//);
    expect(qr?.getAttribute('alt')).toBe('QR code for ticket T-1001');
    fixture.destroy();
  });

  it('reloads the ticket when the bookingId route param changes', async () => {
    const fixture = TestBed.createComponent(TicketPageComponent);
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/ticket`).flush(ticketFixture());
    fixture.detectChanges();

    paramMap$.next(convertToParamMap({ bookingId: 'booking-2' }));
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-2/ticket`).flush(
      ticketFixture({
        bookingId: 'booking-2',
        ticketNumber: 'T-2002',
        bookingReference: 'BB-2002'
      })
    );
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('T-2002');
    expect(text).toContain('BB-2002');
    expect(text).not.toContain('T-1001');
    fixture.destroy();
  });

  it('shows a not-available message when the ticket GET returns 404', () => {
    const fixture = TestBed.createComponent(TicketPageComponent);
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/ticket`).flush(
      { message: 'not found' },
      { status: 404, statusText: 'Not Found' }
    );
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Ticket is not available yet.');
    fixture.destroy();
  });

  it('shows a generic error without exposing HTTP details', () => {
    const fixture = TestBed.createComponent(TicketPageComponent);
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/ticket`).flush(
      { message: 'boom\tat com.example.Service' },
      { status: 500, statusText: 'Server Error' }
    );
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Unable to load your ticket. Please try again.');
    expect(text).not.toContain('boom');
    expect(text).not.toContain('Server Error');
    fixture.destroy();
  });

  it('downloads the official PDF for the current booking', async () => {
    const createObjectURL = spyOn(URL, 'createObjectURL').and.returnValue('blob:ticket-pdf');
    const revokeObjectURL = spyOn(URL, 'revokeObjectURL');
    const click = spyOn(HTMLAnchorElement.prototype, 'click');

    const fixture = TestBed.createComponent(TicketPageComponent);
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/ticket`).flush(ticketFixture());
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const download = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button')
    ).find((button) => button.textContent?.includes('Download PDF'));
    expect(download).withContext('Download PDF button').toBeTruthy();
    download?.click();
    fixture.detectChanges();

    const req = http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/ticket/pdf`);
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['%PDF-1.4 ticket'], { type: 'application/pdf' }));
    fixture.detectChanges();

    expect(createObjectURL).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:ticket-pdf');
    fixture.destroy();
  });

  it('shows a download error without exposing HTTP details', async () => {
    const createObjectURL = spyOn(URL, 'createObjectURL');
    const fixture = TestBed.createComponent(TicketPageComponent);
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/ticket`).flush(ticketFixture());
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const download = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button')
    ).find((button) => button.textContent?.includes('Download PDF'));
    download?.click();
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/ticket/pdf`).flush(
      new Blob(['not found'], { type: 'application/json' }),
      { status: 404, statusText: 'Not Found' }
    );
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Ticket PDF is not available.');
    expect(text).not.toContain('not found');
    expect(createObjectURL).not.toHaveBeenCalled();
    fixture.destroy();
  });
});
