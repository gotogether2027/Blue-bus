import {
  operatorBusFixture,
  operatorRouteFixture,
  operatorTripFixture,
  operatorTripSeatInventoryFixture
} from '../../../testing/operator-fixtures';
import { OperatorTrip } from '../models/operator.models';
import { buildOperationalAlerts } from './operator-operational-alerts';

describe('buildOperationalAlerts', () => {
  const nowMs = Date.parse('2026-09-18T12:00:00Z');

  it('creates a blocked-seat warning from nested trip-list inventory', () => {
    const alerts = buildOperationalAlerts(
      'operator-1',
      [operatorBusFixture()],
      [operatorRouteFixture()],
      [
        futureTrip({
          seatInventory: [
            operatorTripSeatInventoryFixture(),
            operatorTripSeatInventoryFixture({
              id: 'inventory-2',
              seatNumber: 'U2',
              physicalStatus: 'BLOCKED'
            }),
            operatorTripSeatInventoryFixture({
              id: 'inventory-3',
              seatNumber: 'U3',
              physicalStatus: 'BLOCKED'
            })
          ]
        })
      ],
      nowMs
    );

    expect(alerts).toEqual([
      jasmine.objectContaining({
        kind: 'BLOCKED_SEATS',
        severity: 'WARNING',
        tripId: 'trip-1',
        message: '2 physical seats are blocked on the upcoming Coastal Sleeper trip.'
      })
    ]);
    expect(alerts[0].links.map((link) => link.commands)).toEqual([
      ['/operator', 'operator-1', 'trips', 'trip-1'],
      ['/operator', 'operator-1', 'trips', 'trip-1', 'inventory']
    ]);
  });

  it('creates a cancelled future-departure warning without treating it as upcoming inventory', () => {
    const alerts = buildOperationalAlerts(
      'operator-1',
      [operatorBusFixture()],
      [operatorRouteFixture()],
      [
        futureTrip({
          id: 'trip-cancelled',
          status: 'CANCELLED',
          seatInventory: [
            operatorTripSeatInventoryFixture({ physicalStatus: 'BLOCKED' })
          ]
        })
      ],
      nowMs
    );

    expect(alerts.map((alert) => alert.kind)).toEqual([
      'CANCELLED_UPCOMING_TRIP',
      'NO_UPCOMING_TRIPS'
    ]);
    expect(alerts[0].message).toBe(
      'The Coastal Sleeper trip with a future departure is cancelled.'
    );
    expect(alerts[0].links[0].commands).toEqual([
      '/operator',
      'operator-1',
      'trips',
      'trip-cancelled'
    ]);
  });

  it('creates an informational empty-schedule signal when no upcoming trips exist', () => {
    const alerts = buildOperationalAlerts(
      'operator-1',
      [operatorBusFixture()],
      [operatorRouteFixture()],
      [],
      nowMs
    );

    expect(alerts).toEqual([
      jasmine.objectContaining({
        kind: 'NO_UPCOMING_TRIPS',
        severity: 'INFO',
        message: 'No upcoming trips are listed for this operator.'
      })
    ]);
    expect(alerts[0].links[0].commands).toEqual(['/operator', 'operator-1', 'trips']);
  });

  it('does not invent alerts for a healthy upcoming trip', () => {
    const alerts = buildOperationalAlerts(
      'operator-1',
      [operatorBusFixture()],
      [operatorRouteFixture()],
      [futureTrip()],
      nowMs
    );

    expect(alerts).toEqual([]);
  });

  it('ignores trips and inventory that belong to another operator', () => {
    const alerts = buildOperationalAlerts(
      'operator-1',
      [operatorBusFixture()],
      [operatorRouteFixture()],
      [
        futureTrip({
          operatorId: 'operator-2',
          seatInventory: [operatorTripSeatInventoryFixture({ physicalStatus: 'BLOCKED' })]
        })
      ],
      nowMs
    );

    expect(alerts.map((alert) => alert.kind)).toEqual(['NO_UPCOMING_TRIPS']);
  });

  function futureTrip(overrides: Partial<OperatorTrip> = {}): OperatorTrip {
    return operatorTripFixture({
      scheduledDepartureAt: '2026-09-19T01:30:00Z',
      scheduledArrivalAt: '2026-09-19T07:30:00Z',
      serviceDate: '2026-09-19',
      ...overrides
    });
  }
});
