import { TripStatus } from '../../core/api/models';
import { OperatorTrip } from '../models/operator.models';
import { physicalSeatCounts, scopedToOperator } from './operator-dashboard-summary';

export function tripCountByStatus(
  trips: OperatorTrip[],
  operatorId: string,
  status: TripStatus
): number {
  return scopedToOperator(trips, operatorId).filter((trip) => trip.status === status).length;
}

export function physicalInventoryOnTrips(
  trips: OperatorTrip[],
  operatorId: string
): { available: number; blocked: number; total: number } {
  return scopedToOperator(trips, operatorId).reduce(
    (totals, trip) => {
      const seats = physicalSeatCounts(trip.seatInventory);
      return {
        available: totals.available + seats.available,
        blocked: totals.blocked + seats.blocked,
        total: totals.total + seats.total
      };
    },
    { available: 0, blocked: 0, total: 0 }
  );
}

export function sortedReportTrips(trips: OperatorTrip[], operatorId: string): OperatorTrip[] {
  return scopedToOperator(trips, operatorId).sort((left, right) => {
    const byDate = left.serviceDate.localeCompare(right.serviceDate);
    if (byDate !== 0) {
      return byDate;
    }
    return Date.parse(left.scheduledDepartureAt) - Date.parse(right.scheduledDepartureAt);
  });
}
