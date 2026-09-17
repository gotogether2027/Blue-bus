import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PaymentAttempt, PaymentInitiation, RazorpayCheckoutVerificationRequest } from './models';

@Injectable({ providedIn: 'root' })
export class PaymentsService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  listByBooking(bookingId: string): Observable<PaymentAttempt[]> {
    return this.http.get<PaymentAttempt[]>(`${this.base}/bookings/${bookingId}/payments`);
  }

  get(paymentAttemptId: string): Observable<PaymentAttempt> {
    return this.http.get<PaymentAttempt>(`${this.base}/payments/${paymentAttemptId}`);
  }

  initiate(bookingId: string, idempotencyKey: string): Observable<PaymentInitiation> {
    return this.http.post<PaymentInitiation>(`${this.base}/bookings/${bookingId}/payments`, {}, {
      headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey })
    });
  }

  verifyCheckout(
    paymentAttemptId: string,
    request: RazorpayCheckoutVerificationRequest
  ): Observable<PaymentAttempt> {
    return this.http.post<PaymentAttempt>(`${this.base}/payments/${paymentAttemptId}/checkout`, request);
  }
}
