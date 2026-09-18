import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  OperatorBooking,
  OperatorBus,
  OperatorMembership,
  OperatorProfile,
  OperatorTrip,
  OperatorTripFilters
} from '../models/operator.models';

@Injectable({ providedIn: 'root' })
export class OperatorApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  listMemberships(): Observable<OperatorMembership[]> {
    return this.http.get<OperatorMembership[]>(`${this.base}/auth/operator-memberships`);
  }

  getOperator(operatorId: string): Observable<OperatorProfile> {
    return this.http.get<OperatorProfile>(`${this.operatorBase(operatorId)}`);
  }

  listBuses(operatorId: string): Observable<OperatorBus[]> {
    return this.http.get<OperatorBus[]>(`${this.operatorBase(operatorId)}/buses`);
  }

  getBus(operatorId: string, busId: string): Observable<OperatorBus> {
    return this.http.get<OperatorBus>(
      `${this.operatorBase(operatorId)}/buses/${encodeURIComponent(busId)}`
    );
  }

  listTrips(operatorId: string, filters: OperatorTripFilters = {}): Observable<OperatorTrip[]> {
    let params = new HttpParams();
    if (filters.serviceDate) {
      params = params.set('serviceDate', filters.serviceDate);
    }
    if (filters.status) {
      params = params.set('status', filters.status);
    }
    return this.http.get<OperatorTrip[]>(`${this.operatorBase(operatorId)}/trips`, { params });
  }

  getTrip(operatorId: string, tripId: string): Observable<OperatorTrip> {
    return this.http.get<OperatorTrip>(
      `${this.operatorBase(operatorId)}/trips/${encodeURIComponent(tripId)}`
    );
  }

  listTripBookings(operatorId: string, tripId: string): Observable<OperatorBooking[]> {
    return this.http.get<OperatorBooking[]>(
      `${this.operatorBase(operatorId)}/trips/${encodeURIComponent(tripId)}/bookings`
    );
  }

  getTripBooking(
    operatorId: string,
    tripId: string,
    bookingId: string
  ): Observable<OperatorBooking> {
    return this.http.get<OperatorBooking>(
      `${this.operatorBase(operatorId)}/trips/${encodeURIComponent(tripId)}/bookings/${encodeURIComponent(bookingId)}`
    );
  }

  private operatorBase(operatorId: string): string {
    return `${this.base}/operator/${encodeURIComponent(operatorId)}`;
  }
}
