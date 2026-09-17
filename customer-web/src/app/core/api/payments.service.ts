import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PaymentAttempt } from './models';

@Injectable({ providedIn: 'root' })
export class PaymentsService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  listByBooking(bookingId: string): Observable<PaymentAttempt[]> {
    return this.http.get<PaymentAttempt[]>(`${this.base}/bookings/${bookingId}/payments`);
  }
}
