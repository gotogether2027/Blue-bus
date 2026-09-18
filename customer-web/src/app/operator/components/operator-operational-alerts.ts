import { OperatorBus, OperatorRoute, OperatorTrip } from '../models/operator.models';
import { busSummary, routeSummary } from './operator-trip-references';
import { physicalSeatCounts, scopedToOperator, upcomingTrips } from './operator-dashboard-summary';

export type OperatorAlertSeverity = 'INFO' | 'WARNING';

export type OperatorAlertKind =
  | 'BLOCKED_SEATS'
  | 'CANCELLED_UPCOMING_TRIP'
  | 'DRAFT_FUTURE_TRIP'
  | 'NO_UPCOMING_TRIPS';

export interface OperatorAlertLink {
  label: string;
  commands: string[];
}

export interface OperatorOperationalAlert {
  kind: OperatorAlertKind;
  severity: OperatorAlertSeverity;
  title: string;
  message: string;
  tripId?: string;
  links: OperatorAlertLink[];
}

export function buildOperationalAlerts(
  operatorId: string,
  buses: OperatorBus[],
  routes: OperatorRoute[],
  trips: OperatorTrip[],
  nowMs = Date.now()
): OperatorOperationalAlert[] {
  const ownedBuses = scopedToOperator(buses, operatorId);
  const ownedRoutes = scopedToOperator(routes, operatorId);
  const ownedTrips = scopedToOperator(trips, operatorId);
  const upcoming = upcomingTrips(ownedTrips, operatorId, nowMs);
  const alerts: OperatorOperationalAlert[] = [];

  for (const trip of futureTripsWithStatus(ownedTrips, 'CANCELLED', nowMs)) {
    alerts.push({
      kind: 'CANCELLED_UPCOMING_TRIP',
      severity: 'WARNING',
      title: 'Cancelled future trip',
      tripId: trip.id,
      message: `The ${tripLabel(trip, ownedBuses, ownedRoutes)} trip with a future departure is cancelled.`,
      links: [tripLink(operatorId, trip.id, 'View trip')]
    });
  }

  for (const trip of futureTripsWithStatus(ownedTrips, 'DRAFT', nowMs)) {
    alerts.push({
      kind: 'DRAFT_FUTURE_TRIP',
      severity: 'WARNING',
      title: 'Draft future trip',
      tripId: trip.id,
      message: `The ${tripLabel(trip, ownedBuses, ownedRoutes)} trip with a future departure is still in DRAFT.`,
      links: [tripLink(operatorId, trip.id, 'View trip')]
    });
  }

  for (const trip of upcoming) {
    const blocked = physicalSeatCounts(trip.seatInventory).blocked;
    if (blocked === 0) {
      continue;
    }
    const label = tripLabel(trip, ownedBuses, ownedRoutes);
    alerts.push({
      kind: 'BLOCKED_SEATS',
      severity: 'WARNING',
      title: 'Physical blocked seats',
      tripId: trip.id,
      message:
        blocked === 1
          ? `1 physical seat is blocked on the upcoming ${label} trip.`
          : `${blocked} physical seats are blocked on the upcoming ${label} trip.`,
      links: [
        tripLink(operatorId, trip.id, 'View trip'),
        {
          label: 'Inventory',
          commands: ['/operator', operatorId, 'trips', trip.id, 'inventory']
        }
      ]
    });
  }

  if (upcoming.length === 0) {
    alerts.push({
      kind: 'NO_UPCOMING_TRIPS',
      severity: 'INFO',
      title: 'Schedule',
      message: 'No upcoming trips are listed for this operator.',
      links: [{ label: 'View schedule', commands: ['/operator', operatorId, 'trips'] }]
    });
  }

  return alerts;
}

function futureTripsWithStatus(
  trips: OperatorTrip[],
  status: OperatorTrip['status'],
  nowMs: number
): OperatorTrip[] {
  return trips
    .filter((trip) => {
      const departure = Date.parse(trip.scheduledDepartureAt);
      return trip.status === status && !Number.isNaN(departure) && departure >= nowMs;
    })
    .sort(
      (left, right) =>
        Date.parse(left.scheduledDepartureAt) - Date.parse(right.scheduledDepartureAt)
    );
}

function tripLabel(
  trip: OperatorTrip,
  buses: OperatorBus[],
  routes: OperatorRoute[]
): string {
  const bus = buses.find((item) => item.id === trip.busId);
  if (bus) {
    return busSummary(bus, trip.busId);
  }
  const route = routes.find((item) => item.id === trip.routeId);
  if (route) {
    return route.code || routeSummary(route, trip.routeId);
  }
  return 'listed';
}

function tripLink(operatorId: string, tripId: string, label: string): OperatorAlertLink {
  return {
    label,
    commands: ['/operator', operatorId, 'trips', tripId]
  };
}
