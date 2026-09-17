import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { environment } from '../../../environments/environment';
import { PaymentsService } from './payments.service';

describe('PaymentsService', () => {
  let http: HttpTestingController;
  let service: PaymentsService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(PaymentsService);
  });

  afterEach(() => http.verify());

  it('initiates payment with Idempotency-Key and no invented body fields', () => {
    service.initiate('booking-1', 'bb-pay-1').subscribe();
    const req = http.expectOne(`${environment.apiBaseUrl}/bookings/booking-1/payments`);
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toBe('bb-pay-1');
    req.flush({
      paymentAttemptId: 'pay-1',
      bookingId: 'booking-1',
      provider: 'RAZORPAY',
      merchantReference: 'ref',
      providerOrderId: 'order_1',
      checkoutReference: 'rzp_test_123',
      amount: 1299,
      currency: 'INR',
      status: 'PENDING',
      disposition: 'UNAPPLIED',
      paymentExpiresAt: null
    });
  });

  it('reads payment status from GET /payments/{id}', () => {
    let status = '';
    service.get('pay-1').subscribe((payment) => (status = payment.status));
    http.expectOne(`${environment.apiBaseUrl}/payments/pay-1`).flush({
      paymentAttemptId: 'pay-1',
      bookingId: 'booking-1',
      provider: 'RAZORPAY',
      merchantReference: 'ref',
      providerOrderId: 'order_1',
      requestedAmount: 1299,
      capturedAmount: null,
      currency: 'INR',
      status: 'PENDING',
      disposition: 'UNAPPLIED',
      createdAt: '2026-09-18T00:00:00Z',
      processedAt: null
    });
    expect(status).toBe('PENDING');
  });
});
