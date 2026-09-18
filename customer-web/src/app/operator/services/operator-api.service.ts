import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CreateOperatorBusRequest,
  CreateOperatorRoutePointRequest,
  CreateOperatorRouteRequest,
  CreateOperatorRouteStopRequest,
  OperatorBooking,
  OperatorBus,
  OperatorBusType,
  OperatorMembership,
  OperatorProfile,
  OperatorRoute,
  OperatorRoutePoint,
  OperatorRouteStatus,
  OperatorRouteStop,
  OperatorSeatLayout,
  OperatorTrip,
  OperatorTripFilters,
  UpdateOperatorBusRequest,
  UpdateOperatorRoutePointRequest,
  UpdateOperatorRouteRequest,
  UpdateOperatorRouteStopRequest
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

  listRoutes(
    operatorId: string,
    status?: OperatorRouteStatus
  ): Observable<OperatorRoute[]> {
    let params = new HttpParams();
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<OperatorRoute[]>(`${this.operatorBase(operatorId)}/routes`, {
      params
    });
  }

  getRoute(operatorId: string, routeId: string): Observable<OperatorRoute> {
    return this.http.get<OperatorRoute>(
      `${this.operatorBase(operatorId)}/routes/${encodeURIComponent(routeId)}`
    );
  }

  createRoute(
    operatorId: string,
    request: CreateOperatorRouteRequest
  ): Observable<OperatorRoute> {
    return this.http.post<OperatorRoute>(
      `${this.operatorBase(operatorId)}/routes`,
      request
    );
  }

  updateRoute(
    operatorId: string,
    routeId: string,
    request: UpdateOperatorRouteRequest
  ): Observable<OperatorRoute> {
    return this.http.patch<OperatorRoute>(
      `${this.operatorBase(operatorId)}/routes/${encodeURIComponent(routeId)}`,
      request
    );
  }

  activateRoute(operatorId: string, routeId: string): Observable<OperatorRoute> {
    return this.changeRouteLifecycle(operatorId, routeId, 'activate');
  }

  deactivateRoute(operatorId: string, routeId: string): Observable<OperatorRoute> {
    return this.changeRouteLifecycle(operatorId, routeId, 'deactivate');
  }

  addRouteStop(
    operatorId: string,
    routeId: string,
    request: CreateOperatorRouteStopRequest
  ): Observable<OperatorRouteStop> {
    return this.http.post<OperatorRouteStop>(
      `${this.operatorBase(operatorId)}/routes/${encodeURIComponent(routeId)}/stops`,
      request
    );
  }

  updateRouteStop(
    operatorId: string,
    routeId: string,
    stopId: string,
    request: UpdateOperatorRouteStopRequest
  ): Observable<OperatorRouteStop> {
    return this.http.patch<OperatorRouteStop>(
      `${this.operatorBase(operatorId)}/routes/${encodeURIComponent(routeId)}/stops/${encodeURIComponent(stopId)}`,
      request
    );
  }

  addRoutePoint(
    operatorId: string,
    routeId: string,
    stopId: string,
    request: CreateOperatorRoutePointRequest
  ): Observable<OperatorRoutePoint> {
    return this.http.post<OperatorRoutePoint>(
      `${this.operatorBase(operatorId)}/routes/${encodeURIComponent(routeId)}/stops/${encodeURIComponent(stopId)}/points`,
      request
    );
  }

  updateRoutePoint(
    operatorId: string,
    routeId: string,
    stopId: string,
    pointId: string,
    request: UpdateOperatorRoutePointRequest
  ): Observable<OperatorRoutePoint> {
    return this.http.patch<OperatorRoutePoint>(
      `${this.operatorBase(operatorId)}/routes/${encodeURIComponent(routeId)}/stops/${encodeURIComponent(stopId)}/points/${encodeURIComponent(pointId)}`,
      request
    );
  }

  activateRoutePoint(
    operatorId: string,
    routeId: string,
    pointId: string
  ): Observable<OperatorRoutePoint> {
    return this.changeRoutePointLifecycle(operatorId, routeId, pointId, 'activate');
  }

  deactivateRoutePoint(
    operatorId: string,
    routeId: string,
    pointId: string
  ): Observable<OperatorRoutePoint> {
    return this.changeRoutePointLifecycle(operatorId, routeId, pointId, 'deactivate');
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

  private changeRouteLifecycle(
    operatorId: string,
    routeId: string,
    action: 'activate' | 'deactivate'
  ): Observable<OperatorRoute> {
    return this.http.post<OperatorRoute>(
      `${this.operatorBase(operatorId)}/routes/${encodeURIComponent(routeId)}/${action}`,
      null
    );
  }

  private changeRoutePointLifecycle(
    operatorId: string,
    routeId: string,
    pointId: string,
    action: 'activate' | 'deactivate'
  ): Observable<OperatorRoutePoint> {
    return this.http.post<OperatorRoutePoint>(
      `${this.operatorBase(operatorId)}/routes/${encodeURIComponent(routeId)}/points/${encodeURIComponent(pointId)}/${action}`,
      null
    );
  }
}
