import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CreateOperatorBusRequest,
  OperatorBooking,
  OperatorBus,
  OperatorBusType,
  OperatorMembership,
  OperatorProfile,
  OperatorSeatLayout,
  OperatorTrip,
  OperatorTripFilters,
  UpdateOperatorBusRequest
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

  listActiveBusTypes(operatorId: string): Observable<OperatorBusType[]> {
    return this.http.get<OperatorBusType[]>(`${this.operatorBase(operatorId)}/bus-types`);
  }

  listPublishedSeatLayouts(operatorId: string): Observable<OperatorSeatLayout[]> {
    const params = new HttpParams().set('status', 'PUBLISHED');
    return this.http.get<OperatorSeatLayout[]>(
      `${this.operatorBase(operatorId)}/seat-layouts`,
      { params }
    );
  }

  createBus(
    operatorId: string,
    request: CreateOperatorBusRequest
  ): Observable<OperatorBus> {
    return this.http.post<OperatorBus>(`${this.operatorBase(operatorId)}/buses`, request);
  }

  updateBus(
    operatorId: string,
    busId: string,
    request: UpdateOperatorBusRequest
  ): Observable<OperatorBus> {
    return this.http.patch<OperatorBus>(
      `${this.operatorBase(operatorId)}/buses/${encodeURIComponent(busId)}`,
      request
    );
  }

  activateBus(operatorId: string, busId: string): Observable<OperatorBus> {
    return this.changeBusLifecycle(operatorId, busId, 'activate');
  }

  deactivateBus(operatorId: string, busId: string): Observable<OperatorBus> {
    return this.changeBusLifecycle(operatorId, busId, 'deactivate');
  }

  markBusMaintenance(operatorId: string, busId: string): Observable<OperatorBus> {
    return this.changeBusLifecycle(operatorId, busId, 'maintenance');
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

  private changeBusLifecycle(
    operatorId: string,
    busId: string,
    action: 'activate' | 'deactivate' | 'maintenance'
  ): Observable<OperatorBus> {
    return this.http.post<OperatorBus>(
      `${this.operatorBase(operatorId)}/buses/${encodeURIComponent(busId)}/${action}`,
      null
    );
  }
}
