import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CustomerLocation } from './models';

@Injectable({ providedIn: 'root' })
export class LocationsService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  list(filters?: { state?: string; city?: string }): Observable<CustomerLocation[]> {
    let params = new HttpParams();
    const state = filters?.state?.trim();
    const city = filters?.city?.trim();
    if (state) {
      params = params.set('state', state);
    }
    if (city) {
      params = params.set('city', city);
    }
    return this.http.get<CustomerLocation[]>(`${this.base}/locations`, { params });
  }
}
