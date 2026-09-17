import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CreateSeatHoldRequest, SeatHold } from './models';

@Injectable({ providedIn: 'root' })
export class HoldsService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  create(tripId: string, request: CreateSeatHoldRequest): Observable<SeatHold> {
    return this.http.post<SeatHold>(`${this.base}/trips/${tripId}/holds`, request);
  }

  get(holdId: string): Observable<SeatHold> {
    return this.http.get<SeatHold>(`${this.base}/holds/${holdId}`);
  }

  cancel(holdId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/holds/${holdId}`);
  }
}
