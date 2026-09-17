import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Booking, BookingCancellation, CancelBookingRequest } from './models';

@Injectable({ providedIn: 'root' })
export class BookingsService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  list(): Observable<Booking[]> {
    return this.http.get<Booking[]>(`${this.base}/bookings`);
  }

  get(bookingId: string): Observable<Booking> {
    return this.http.get<Booking>(`${this.base}/bookings/${bookingId}`);
  }

  cancel(bookingId: string, request?: CancelBookingRequest): Observable<BookingCancellation> {
    return this.http.post<BookingCancellation>(`${this.base}/bookings/${bookingId}/cancel`, request ?? {});
  }
}
