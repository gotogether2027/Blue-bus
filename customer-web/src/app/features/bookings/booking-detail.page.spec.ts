import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { environment } from '../../../environments/environment';
import { bookingFixture } from '../../../testing/fixtures';
import { BookingDetailPageComponent } from './booking-detail.page';

describe('BookingDetailPageComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BookingDetailPageComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: () => 'booking-1' } } }
        }
      ]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('downloads the official PDF from booking details', () => {
    const createObjectURL = spyOn(URL, 'createObjectURL').and.returnValue('blob:ticket-pdf');
    const click = spyOn(HTMLAnchorElement.prototype, 'click');

    const fixture = TestBed.createComponent(BookingDetailPageComponent);
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1`).flush(
      bookingFixture({
        status: 'CONFIRMED',
        ticketId: 'ticket-1',
        ticketNumber: 'T-1001',
        ticketStatus: 'ACTIVE'
      })
    );
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('View full ticket');
    const download = Array.from(root.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Download PDF')
    );
    expect(download).withContext('Download PDF button').toBeTruthy();
    download?.click();
    fixture.detectChanges();

    const req = http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/ticket/pdf`);
    expect(req.request.method).toBe('GET');
    req.flush(new Blob(['%PDF-1.4'], { type: 'application/pdf' }));
    fixture.detectChanges();

    expect(createObjectURL).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    fixture.destroy();
  });

  it('surfaces a PDF download error on booking details', () => {
    const fixture = TestBed.createComponent(BookingDetailPageComponent);
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1`).flush(
      bookingFixture({
        status: 'CONFIRMED',
        ticketId: 'ticket-1',
        ticketNumber: 'T-1001',
        ticketStatus: 'ACTIVE'
      })
    );
    fixture.detectChanges();

    const download = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button')
    ).find((button) => button.textContent?.includes('Download PDF'));
    download?.click();
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/ticket/pdf`).flush(
      new Blob(['denied'], { type: 'application/json' }),
      { status: 401, statusText: 'Unauthorized' }
    );
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Please sign in to download this ticket.');
    expect(text).not.toContain('denied');
    fixture.destroy();
  });
});
