import {
  OperatorBooking,
  OperatorBus,
  OperatorBusType,
  OperatorMembership,
  OperatorProfile,
  OperatorRoute,
  OperatorRoutePoint,
  OperatorRouteStop,
  OperatorSeatLayout,
  OperatorTrip,
  OperatorTripSeatInventory
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

export const operatorRoutePointFixture = (
  overrides: Partial<OperatorRoutePoint> = {}
): OperatorRoutePoint => ({
  id: 'route-point-1',
  routeStopId: 'route-stop-1',
  name: 'Miyapur',
  pointType: 'BOARDING',
  address: 'Miyapur X Roads',
  latitude: 17.5,
  longitude: 78.3,
  active: true,
  ...overrides
});

export const operatorRouteStopFixture = (
  overrides: Partial<OperatorRouteStop> = {}
): OperatorRouteStop => ({
  id: 'route-stop-1',
  routeId: 'route-1',
  locationId: 'location-hyd',
  sequenceNumber: 1,
  stopKind: 'SOURCE',
  arrivalOffsetMinutes: null,
  departureOffsetMinutes: 0,
  distanceKm: 0,
  points: [operatorRoutePointFixture()],
  ...overrides
});

export const operatorRouteFixture = (
  overrides: Partial<OperatorRoute> = {}
): OperatorRoute => ({
  id: 'route-1',
  operatorId: 'operator-1',
  code: 'HYD-VJA',
  name: 'Hyderabad to Vijayawada',
  sourceLocationId: 'location-hyd',
  destinationLocationId: 'location-vja',
  status: 'ACTIVE',
  stops: [
    operatorRouteStopFixture(),
    operatorRouteStopFixture({
      id: 'route-stop-2',
      locationId: 'location-vja',
      sequenceNumber: 2,
      stopKind: 'DESTINATION',
      arrivalOffsetMinutes: 270,
      departureOffsetMinutes: null,
      distanceKm: 270,
      points: [
        operatorRoutePointFixture({
          id: 'route-point-2',
          routeStopId: 'route-stop-2',
          name: 'Benz Circle',
          pointType: 'DROPPING',
          address: null,
          latitude: null,
          longitude: null
        })
      ]
    })
  ],
  ...overrides
});

export const operatorTripSeatInventoryFixture = (
  overrides: Partial<OperatorTripSeatInventory> = {}
): OperatorTripSeatInventory => ({
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
  blockReason: null,
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
    operatorTripSeatInventoryFixture()
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

export const operatorCancelledMultiPassengerBookingFixture = (
  overrides: Partial<OperatorBooking> = {}
): OperatorBooking =>
  operatorBookingFixture({
    bookingId: 'booking-2',
    bookingReference: 'BB-2002',
    status: 'CANCELLED',
    totalAmount: 2498,
    items: [
      {
        bookingItemId: 'item-2',
        passengerId: 'passenger-2',
        seatNumber: 'L2',
        seatType: 'SLEEPER',
        originSequence: 1,
        destinationSequence: 2,
        status: 'CANCELLED'
      },
      {
        bookingItemId: 'item-3',
        passengerId: 'passenger-3',
        seatNumber: 'L3',
        seatType: 'SLEEPER',
        originSequence: 1,
        destinationSequence: 2,
        status: 'CANCELLED'
      }
    ],
    passengers: [
      {
        passengerId: 'passenger-2',
        fullName: 'Ravi Kumar',
        age: 41,
        gender: 'MALE'
      },
      {
        passengerId: 'passenger-3',
        fullName: 'Meera Iyer',
        age: 28,
        gender: 'FEMALE'
      }
    ],
    ...overrides
  });

export const operatorPendingPaymentBookingFixture = (
  overrides: Partial<OperatorBooking> = {}
): OperatorBooking =>
  operatorBookingFixture({
    bookingId: 'booking-3',
    bookingReference: 'BB-3003',
    status: 'PENDING_PAYMENT',
    totalAmount: 899,
    items: [
      {
        bookingItemId: 'item-4',
        passengerId: 'passenger-4',
        seatNumber: 'U4',
        seatType: 'SLEEPER',
        originSequence: 1,
        destinationSequence: 2,
        status: 'ACTIVE'
      }
    ],
    passengers: [
      {
        passengerId: 'passenger-4',
        fullName: 'Kiran Shah',
        age: 26,
        gender: 'MALE'
      }
    ],
    ...overrides
  });

export const operatorSharedSeatSegmentBookings = (): OperatorBooking[] => [
  operatorBookingFixture({
    bookingId: 'booking-seg-a',
    bookingReference: 'BB-4001',
    originSequence: 1,
    destinationSequence: 2,
    originTripStopId: 'stop-hyd',
    destinationTripStopId: 'stop-vja',
    items: [
      {
        bookingItemId: 'item-seg-a',
        passengerId: 'passenger-seg-a',
        seatNumber: 'R2',
        seatType: 'SEATER',
        originSequence: 1,
        destinationSequence: 2,
        status: 'ACTIVE'
      }
    ],
    passengers: [
      {
        passengerId: 'passenger-seg-a',
        fullName: 'Passenger A',
        age: 34,
        gender: 'FEMALE'
      }
    ],
    trip: {
      ...operatorBookingFixture().trip,
      origin: {
        ...operatorBookingFixture().trip.origin,
        tripStopId: 'stop-hyd',
        locationId: 'location-hyd',
        sequenceNumber: 1,
        city: 'Hyderabad'
      },
      destination: {
        ...operatorBookingFixture().trip.destination,
        tripStopId: 'stop-vja',
        locationId: 'location-vja',
        sequenceNumber: 2,
        city: 'Vijayawada'
      }
    }
  }),
  operatorBookingFixture({
    bookingId: 'booking-seg-b',
    bookingReference: 'BB-4002',
    originSequence: 2,
    destinationSequence: 3,
    originTripStopId: 'stop-vja',
    destinationTripStopId: 'stop-gnt',
    items: [
      {
        bookingItemId: 'item-seg-b',
        passengerId: 'passenger-seg-b',
        seatNumber: 'R2',
        seatType: 'SEATER',
        originSequence: 2,
        destinationSequence: 3,
        status: 'ACTIVE'
      }
    ],
    passengers: [
      {
        passengerId: 'passenger-seg-b',
        fullName: 'Passenger B',
        age: 29,
        gender: 'MALE'
      }
    ],
    trip: {
      ...operatorBookingFixture().trip,
      origin: {
        ...operatorBookingFixture().trip.origin,
        tripStopId: 'stop-vja',
        locationId: 'location-vja',
        sequenceNumber: 2,
        city: 'Vijayawada'
      },
      destination: {
        ...operatorBookingFixture().trip.destination,
        tripStopId: 'stop-gnt',
        locationId: 'location-gnt',
        sequenceNumber: 3,
        city: 'Guntur'
      }
    }
  })
];
