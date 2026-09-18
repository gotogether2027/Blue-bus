import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';
import { CustomerLocation } from '../../core/api/models';
import { locationLabel } from '../../shared/format';
import {
  CreateOperatorRoutePointRequest,
  CreateOperatorRouteStopRequest,
  OperatorRouteStop,
  OperatorTrip,
  UpdateOperatorRoutePointRequest,
  UpdateOperatorRouteStopRequest
} from '../models/operator.models';

export const ROUTE_STOP_KINDS = ['SOURCE', 'INTERMEDIATE', 'DESTINATION'] as const;
export const ROUTE_POINT_TYPES = ['BOARDING', 'DROPPING', 'BOTH'] as const;

export const STRUCTURAL_ROUTE_RESTRICTION =
  'This route already has trips. Source, destination, stops, and point details cannot be changed. Name, route status, and point activation can still be updated.';

export function orderedRouteStops(stops: OperatorRouteStop[]): OperatorRouteStop[] {
  return [...stops].sort((left, right) => left.sequenceNumber - right.sequenceNumber);
}

export function nextRouteStopSequence(stops: OperatorRouteStop[]): number {
  return stops.reduce((max, stop) => Math.max(max, stop.sequenceNumber), 0) + 1;
}

export function routeHasTrips(trips: OperatorTrip[], routeId: string): boolean {
  return trips.some((trip) => trip.routeId === routeId);
}

export function operatorLocationLabel(
  locations: CustomerLocation[],
  locationId: string
): string {
  const location = locations.find((candidate) => candidate.id === locationId);
  if (!location) {
    return locationId;
  }
  const cityLabel = locationLabel(location);
  return location.state ? `${cityLabel} · ${location.state}` : cityLabel;
}

export function differentLocationsValidator(
  sourceKey: string,
  destinationKey: string
): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const source = control.get(sourceKey)?.value as string | null | undefined;
    const destination = control.get(destinationKey)?.value as string | null | undefined;
    if (source && destination && source === destination) {
      return { sameLocations: true };
    }
    return null;
  };
}

export function stopTimingValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const arrival = toOptionalNumber(control.get('arrivalOffsetMinutes')?.value);
    const departure = toOptionalNumber(control.get('departureOffsetMinutes')?.value);
    if (arrival != null && departure != null && departure < arrival) {
      return { departureBeforeArrival: true };
    }
    return null;
  };
}

export function toOptionalNumber(value: unknown): number | undefined {
  if (value === null || value === undefined || value === '') {
    return undefined;
  }
  const numeric = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(numeric) ? numeric : undefined;
}

export function toStopCreateRequest(value: {
  locationId: string;
  sequenceNumber: number;
  stopKind: CreateOperatorRouteStopRequest['stopKind'];
  arrivalOffsetMinutes?: unknown;
  departureOffsetMinutes?: unknown;
  distanceKm?: unknown;
  points?: CreateOperatorRoutePointRequest[];
}): CreateOperatorRouteStopRequest {
  const request: CreateOperatorRouteStopRequest = {
    locationId: value.locationId,
    sequenceNumber: value.sequenceNumber,
    stopKind: value.stopKind
  };
  const arrivalOffsetMinutes = toOptionalNumber(value.arrivalOffsetMinutes);
  const departureOffsetMinutes = toOptionalNumber(value.departureOffsetMinutes);
  const distanceKm = toOptionalNumber(value.distanceKm);
  if (arrivalOffsetMinutes !== undefined) {
    request.arrivalOffsetMinutes = arrivalOffsetMinutes;
  }
  if (departureOffsetMinutes !== undefined) {
    request.departureOffsetMinutes = departureOffsetMinutes;
  }
  if (distanceKm !== undefined) {
    request.distanceKm = distanceKm;
  }
  if (value.points && value.points.length > 0) {
    request.points = value.points;
  }
  return request;
}

export function toStopUpdateRequest(value: {
  locationId: string;
  sequenceNumber: number;
  stopKind: UpdateOperatorRouteStopRequest['stopKind'];
  arrivalOffsetMinutes?: unknown;
  departureOffsetMinutes?: unknown;
  distanceKm?: unknown;
}): UpdateOperatorRouteStopRequest {
  const request: UpdateOperatorRouteStopRequest = {
    locationId: value.locationId,
    sequenceNumber: value.sequenceNumber,
    stopKind: value.stopKind
  };
  const arrivalOffsetMinutes = toOptionalNumber(value.arrivalOffsetMinutes);
  const departureOffsetMinutes = toOptionalNumber(value.departureOffsetMinutes);
  const distanceKm = toOptionalNumber(value.distanceKm);
  if (arrivalOffsetMinutes !== undefined) {
    request.arrivalOffsetMinutes = arrivalOffsetMinutes;
  }
  if (departureOffsetMinutes !== undefined) {
    request.departureOffsetMinutes = departureOffsetMinutes;
  }
  if (distanceKm !== undefined) {
    request.distanceKm = distanceKm;
  }
  return request;
}

export function toPointMutationRequest(value: {
  name: string;
  pointType: UpdateOperatorRoutePointRequest['pointType'];
  address?: string;
  latitude?: unknown;
  longitude?: unknown;
}): UpdateOperatorRoutePointRequest {
  const request: UpdateOperatorRoutePointRequest = {
    name: value.name.trim(),
    pointType: value.pointType
  };
  const address = value.address?.trim();
  const latitude = toOptionalNumber(value.latitude);
  const longitude = toOptionalNumber(value.longitude);
  if (address) {
    request.address = address;
  }
  if (latitude !== undefined) {
    request.latitude = latitude;
  }
  if (longitude !== undefined) {
    request.longitude = longitude;
  }
  return request;
}
