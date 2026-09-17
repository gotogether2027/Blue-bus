import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { TripSearchResult } from './models';

export interface TripSearchQuery {
  originLocationId: string;
  destinationLocationId: string;
  serviceDate: string;
}

export function toTripSearchParams(query: TripSearchQuery): HttpParams {
  return new HttpParams()
    .set('originLocationId', query.originLocationId)
    .set('destinationLocationId', query.destinationLocationId)
    .set('serviceDate', query.serviceDate);
}

@Injectable({ providedIn: 'root' })
export class TripSearchService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  search(query: TripSearchQuery): Observable<TripSearchResult[]> {
    return this.http.get<TripSearchResult[]>(`${this.base}/search/trips`, {
      params: toTripSearchParams(query)
    });
  }
}
