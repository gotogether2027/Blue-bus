import { OperatorBus, OperatorRoute, OperatorTrip, OperatorTripSeatInventory } from '../models/operator.models';

const EXCLUDED_UPCOMING_STATUSES = new Set(['CANCELLED', 'COMPLETED', 'DEPARTED']);

export function operatorDashboardToday(timeZone = 'Asia/Kolkata', now = new Date()): string {
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  });
  const parts = Object.fromEntries(
    formatter.formatToParts(now).map((part) => [part.type, part.value])
  );
  return `${parts['year']}-${parts['month']}-${parts['day']}`;
}

export function scopedToOperator<T extends { operatorId: string }>(
  items: T[],
  operatorId: string
): T[] {
  return items.filter((item) => item.operatorId === operatorId);
}

export function activeBusCount(buses: OperatorBus[], operatorId: string): number {
  return scopedToOperator(buses, operatorId).filter((bus) => bus.status === 'ACTIVE').length;
}

export function activeRouteCount(routes: OperatorRoute[], operatorId: string): number {
  return scopedToOperator(routes, operatorId).filter((route) => route.status === 'ACTIVE').length;
}

export function tripsOnServiceDate(trips: OperatorTrip[], operatorId: string, serviceDate: string): OperatorTrip[] {
  return scopedToOperator(trips, operatorId).filter((trip) => trip.serviceDate === serviceDate);
}

export function upcomingTrips(
  trips: OperatorTrip[],
  operatorId: string,
  nowMs = Date.now(),
  limit?: number
): OperatorTrip[] {
  const upcoming = scopedToOperator(trips, operatorId)
    .filter((trip) => {
      const departure = Date.parse(trip.scheduledDepartureAt);
      return (
        !Number.isNaN(departure) &&
        departure >= nowMs &&
        !EXCLUDED_UPCOMING_STATUSES.has(trip.status)
      );
    })
    .sort(
      (left, right) =>
        Date.parse(left.scheduledDepartureAt) - Date.parse(right.scheduledDepartureAt)
    );
  return limit == null ? upcoming : upcoming.slice(0, limit);
}

export function physicalSeatCounts(inventory: OperatorTripSeatInventory[] | undefined): {
  available: number;
  blocked: number;
  total: number;
} {
  const seats = inventory ?? [];
  return {
    total: seats.length,
    available: seats.filter((seat) => seat.physicalStatus === 'AVAILABLE').length,
    blocked: seats.filter((seat) => seat.physicalStatus === 'BLOCKED').length
  };
}
