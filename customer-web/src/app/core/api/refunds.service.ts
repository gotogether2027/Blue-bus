import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Refund } from './models';

@Injectable({ providedIn: 'root' })
export class RefundsService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  listByBooking(bookingId: string): Observable<Refund[]> {
    return this.http.get<Refund[]>(`${this.base}/bookings/${bookingId}/refunds`);
  }
}
