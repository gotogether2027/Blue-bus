import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Ticket } from './models';

@Injectable({ providedIn: 'root' })
export class TicketsService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  getByBooking(bookingId: string): Observable<Ticket> {
    return this.http.get<Ticket>(`${this.base}/bookings/${bookingId}/ticket`);
  }

  downloadPdf(bookingId: string): Observable<Blob> {
    return this.http.get(`${this.base}/bookings/${bookingId}/ticket/pdf`, {
      responseType: 'blob'
    });
  }
}
