import {
  OperatorBooking,
  OperatorBus,
  OperatorBusType,
  OperatorMembership,
  OperatorProfile,
  OperatorSeatLayout,
  OperatorTrip
} from '../app/operator/models/operator.models';

export const operatorMembershipFixture = (
  overrides: Partial<OperatorMembership> = {}
): OperatorMembership => ({
  operatorId: 'operator-1',
  operatorDisplayName: 'Coastal Travels',
  role: 'OPERATOR_ADMIN',
  ...overrides
});

export const operatorProfileFixture = (
  overrides: Partial<OperatorProfile> = {}
): OperatorProfile => ({
  id: 'operator-1',
  legalName: 'Coastal Travels Private Limited',
  displayName: 'Coastal Travels',
  status: 'ACTIVE',
  supportEmail: 'ops@example.test',
  supportPhoneE164: '+919876543210',
  ...overrides
});

export const operatorBusFixture = (overrides: Partial<OperatorBus> = {}): OperatorBus => ({
  id: 'bus-1',
  operatorId: 'operator-1',
  busTypeId: 'bus-type-1',
  seatLayoutId: 'layout-1',
  registrationNumber: 'AP31AB1234',
  displayName: 'Coastal Sleeper',
  status: 'ACTIVE',
  ...overrides
});

export const operatorBusTypeFixture = (
  overrides: Partial<OperatorBusType> = {}
): OperatorBusType => ({
  id: 'bus-type-1',
  code: 'AC_SLEEPER',
  displayName: 'AC Sleeper',
  active: true,
  ...overrides
});

export const operatorSeatLayoutFixture = (
  overrides: Partial<OperatorSeatLayout> = {}
): OperatorSeatLayout => ({
  id: 'layout-1',
  operatorId: 'operator-1',
  name: 'Sleeper 2+1',
  version: 1,
  deckCount: 1,
  rowCount: 10,
  columnCount: 3,
  status: 'PUBLISHED',
  seats: [],
  ...overrides
});

export const operatorTripFixture = (
  overrides: Partial<OperatorTrip> = {}
): OperatorTrip => ({
  id: 'trip-1',
  operatorId: 'operator-1',
  busId: 'bus-1',
  routeId: 'route-1',
  seatLayoutId: 'layout-1',
  serviceDate: '2026-12-18',
  timeZone: 'Asia/Kolkata',
  scheduledDepartureAt: '2026-12-18T01:30:00Z',
  scheduledArrivalAt: '2026-12-18T07:30:00Z',
  baseFare: 1299,
  bookingOpensAt: '2026-09-18T00:00:00Z',
  bookingClosesAt: '2026-12-18T00:30:00Z',
  status: 'SCHEDULED',
  stops: [
    {
      id: 'stop-origin',
      tripId: 'trip-1',
      routeStopId: 'route-stop-origin',
      locationId: 'location-origin',
      sequenceNumber: 1,
      stopKind: 'SOURCE',
      stopStatus: 'ACTIVE',
      scheduledArrivalAt: null,
      scheduledDepartureAt: '2026-12-18T01:30:00Z',
      distanceKm: 0,
      points: [
        {
          id: 'point-origin',
          tripStopId: 'stop-origin',
          sourceRoutePointId: 'route-point-origin',
          name: 'RTC Complex',
          pointType: 'BOARDING',
          address: 'RTC Complex',
          latitude: null,
          longitude: null,
          active: true
        }
      ]
    },
    {
      id: 'stop-destination',
      tripId: 'trip-1',
      routeStopId: 'route-stop-destination',
      locationId: 'location-destination',
      sequenceNumber: 2,
      stopKind: 'DESTINATION',
      stopStatus: 'ACTIVE',
      scheduledArrivalAt: '2026-12-18T07:30:00Z',
      scheduledDepartureAt: null,
      distanceKm: 620,
      points: []
    }
  ],
  seatInventory: [
    {
      id: 'inventory-1',
      tripId: 'trip-1',
      layoutSeatId: 'layout-seat-1',
      seatLayoutId: 'layout-1',
      seatLayoutVersion: 1,
      seatNumber: 'U1',
      seatType: 'SLEEPER',
      deckNumber: 1,
      rowNumber: 1,
      columnNumber: 1,
      physicalStatus: 'AVAILABLE',
      blockReason: null
    }
  ],
  ...overrides
});

export const operatorBookingFixture = (
  overrides: Partial<OperatorBooking> = {}
): OperatorBooking => ({
  bookingId: 'booking-1',
  bookingReference: 'BB-1001',
  status: 'CONFIRMED',
  tripId: 'trip-1',
  originSequence: 1,
  destinationSequence: 2,
  originTripStopId: 'stop-origin',
  destinationTripStopId: 'stop-destination',
  currency: 'INR',
  totalAmount: 1299,
  createdAt: '2026-09-18T10:00:00Z',
  items: [
    {
      bookingItemId: 'item-1',
      passengerId: 'passenger-1',
      seatNumber: 'U1',
      seatType: 'SLEEPER',
      originSequence: 1,
      destinationSequence: 2,
      status: 'ACTIVE'
    }
  ],
  passengers: [
    {
      passengerId: 'passenger-1',
      fullName: 'Asha Rao',
      age: 32,
      gender: 'FEMALE'
    }
  ],
  trip: {
    tripId: 'trip-1',
    serviceDate: '2026-12-18',
    timeZone: 'Asia/Kolkata',
    scheduledDepartureAt: '2026-12-18T01:30:00Z',
    scheduledArrivalAt: '2026-12-18T07:30:00Z',
    status: 'SCHEDULED',
    operatorId: 'operator-1',
    operatorName: 'Coastal Travels',
    busId: 'bus-1',
    busRegistrationNumber: 'AP31AB1234',
    busDisplayName: 'Coastal Sleeper',
    routeId: 'route-1',
    routeCode: 'VSKP-HYD',
    routeName: 'Visakhapatnam to Hyderabad',
    origin: {
      tripStopId: 'stop-origin',
      locationId: 'location-origin',
      sequenceNumber: 1,
      city: 'Visakhapatnam',
      state: 'Andhra Pradesh',
      locality: null,
      scheduledArrivalAt: null,
      scheduledDepartureAt: '2026-12-18T01:30:00Z',
      points: []
    },
    destination: {
      tripStopId: 'stop-destination',
      locationId: 'location-destination',
      sequenceNumber: 2,
      city: 'Hyderabad',
      state: 'Telangana',
      locality: null,
      scheduledArrivalAt: '2026-12-18T07:30:00Z',
      scheduledDepartureAt: null,
      points: []
    }
  },
  ...overrides
});
