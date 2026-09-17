import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { TripSeatAvailability } from './models';

@Injectable({ providedIn: 'root' })
export class SeatsService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  availability(tripId: string, originStopId: string, destinationStopId: string): Observable<TripSeatAvailability> {
    const params = new HttpParams()
      .set('originStopId', originStopId)
      .set('destinationStopId', destinationStopId);
    return this.http.get<TripSeatAvailability>(`${this.base}/trips/${tripId}/seat-availability`, { params });
  }
}
