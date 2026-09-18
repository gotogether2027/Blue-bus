import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';
import { TripStatus } from '../../core/api/models';
import { OperatorBus, OperatorRoute } from '../models/operator.models';

export const DEFAULT_TRIP_TIME_ZONE = 'Asia/Kolkata';

export const TRIP_STATUSES: TripStatus[] = [
  'DRAFT',
  'SCHEDULED',
  'ON_SALE',
  'CLOSED',
  'DEPARTED',
  'COMPLETED',
  'CANCELLED'
];

export function eligibleActiveBuses(buses: OperatorBus[], operatorId: string): OperatorBus[] {
  return buses.filter((bus) => bus.operatorId === operatorId && bus.status === 'ACTIVE');
}

export function eligibleActiveRoutesForTrip(
  routes: OperatorRoute[],
  operatorId: string
): OperatorRoute[] {
  return routes.filter(
    (route) =>
      route.operatorId === operatorId && route.status === 'ACTIVE' && route.stops.length >= 2
  );
}

export function canScheduleTrip(status: TripStatus): boolean {
  return status === 'DRAFT';
}

export function canCancelTrip(status: TripStatus): boolean {
  return status !== 'CANCELLED' && status !== 'DEPARTED' && status !== 'COMPLETED';
}

export function canEditTripCommercialTerms(status: TripStatus): boolean {
  return status === 'DRAFT' || status === 'SCHEDULED';
}

export function busSummary(bus: OperatorBus | undefined, busId: string): string {
  if (!bus) {
    return busId;
  }
  return bus.displayName || bus.registrationNumber || busId;
}

export function routeSummary(route: OperatorRoute | undefined, routeId: string): string {
  if (!route) {
    return routeId;
  }
  return `${route.code} · ${route.name}`;
}

export function isoFromWallClock(wallClock: string, timeZone: string): string | null {
  const match = wallClock
    .trim()
    .match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/);
  if (!match) {
    return null;
  }
  const utcGuess = Date.UTC(
    Number(match[1]),
    Number(match[2]) - 1,
    Number(match[3]),
    Number(match[4]),
    Number(match[5]),
    Number(match[6] ?? '0')
  );
  let utcMs = utcGuess - timeZoneOffsetMs(timeZone, utcGuess);
  utcMs = utcGuess - timeZoneOffsetMs(timeZone, utcMs);
  return new Date(utcMs).toISOString();
}

export function wallClockFromIso(iso: string, timeZone: string): string {
  const map = formatParts(iso, timeZone);
  const hour = map['hour'] === '24' ? '00' : map['hour'];
  return `${map['year']}-${map['month']}-${map['day']}T${hour}:${map['minute']}`;
}

export function tripCreateTimingValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const timeZone =
      String(control.get('timeZone')?.value ?? DEFAULT_TRIP_TIME_ZONE).trim() ||
      DEFAULT_TRIP_TIME_ZONE;
    return timingErrors(
      isoFromWallClock(String(control.get('scheduledDepartureAt')?.value ?? ''), timeZone),
      isoFromWallClock(String(control.get('scheduledArrivalAt')?.value ?? ''), timeZone),
      isoFromWallClock(String(control.get('bookingOpensAt')?.value ?? ''), timeZone),
      isoFromWallClock(String(control.get('bookingClosesAt')?.value ?? ''), timeZone)
    );
  };
}

export function tripCommercialTimingValidator(
  scheduledDepartureIso: () => string | null,
  timeZone: () => string
): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const zone = timeZone().trim() || DEFAULT_TRIP_TIME_ZONE;
    return timingErrors(
      scheduledDepartureIso(),
      scheduledDepartureIso(),
      isoFromWallClock(String(control.get('bookingOpensAt')?.value ?? ''), zone),
      isoFromWallClock(String(control.get('bookingClosesAt')?.value ?? ''), zone)
    );
  };
}

function timingErrors(
  departureIso: string | null,
  arrivalIso: string | null,
  opensIso: string | null,
  closesIso: string | null
): ValidationErrors | null {
  const errors: ValidationErrors = {};
  if (departureIso && arrivalIso && arrivalIso !== departureIso) {
    if (Date.parse(arrivalIso) <= Date.parse(departureIso)) {
      errors['arrivalNotAfterDeparture'] = true;
    }
  }
  if (opensIso && closesIso && Date.parse(closesIso) <= Date.parse(opensIso)) {
    errors['bookingCloseNotAfterOpen'] = true;
  }
  if (closesIso && departureIso && Date.parse(closesIso) > Date.parse(departureIso)) {
    errors['bookingClosesAfterDeparture'] = true;
  }
  return Object.keys(errors).length ? errors : null;
}

function timeZoneOffsetMs(timeZone: string, utcMs: number): number {
  const map = formatParts(new Date(utcMs).toISOString(), timeZone);
  const hour = map['hour'] === '24' ? 0 : Number(map['hour']);
  const asUtc = Date.UTC(
    Number(map['year']),
    Number(map['month']) - 1,
    Number(map['day']),
    hour,
    Number(map['minute']),
    Number(map['second'] ?? '0')
  );
  return asUtc - utcMs;
}

function formatParts(iso: string, timeZone: string): Record<string, string> {
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
  return Object.fromEntries(
    formatter.formatToParts(new Date(iso)).map((part) => [part.type, part.value])
  );
}
