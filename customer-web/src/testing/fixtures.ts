import { Booking, CustomerIdentity, CustomerLocation, TripSearchResult } from '../app/core/api/models';

export const identityFixture: CustomerIdentity = {
  userId: 'user-1',
  firstName: 'Asha',
  lastName: 'Rao',
  email: 'asha@example.com',
  roles: ['CUSTOMER'],
  status: 'ACTIVE'
};

export const locationFixture = (id: string, city: string, state = 'Andhra Pradesh'): CustomerLocation => ({
  id,
  city,
  state,
  countryCode: 'IN',
  locality: null
});

export const tripSearchResultFixture = (overrides: Partial<TripSearchResult> = {}): TripSearchResult => ({
  tripId: 'trip-1',
  serviceDate: '2026-09-18',
  timeZone: 'Asia/Kolkata',
  scheduledDepartureAt: '2026-09-18T01:30:00Z',
  scheduledArrivalAt: '2026-09-18T07:30:00Z',
  status: 'ON_SALE',
  operatorId: 'op-1',
  operatorName: 'Coastal Travels',
  busId: 'bus-1',
  busRegistrationNumber: 'AP31AB1234',
  busDisplayName: 'Coastal Sleeper',
  routeId: 'route-1',
  routeCode: 'VSKP-HYD',
  routeName: 'Visakhapatnam to Hyderabad',
  baseFare: 1299,
  currency: 'INR',
  availableSeatCount: 12,
  origin: {
    tripStopId: 'stop-origin',
    locationId: 'loc-origin',
    sequenceNumber: 1,
    city: 'Visakhapatnam',
    state: 'Andhra Pradesh',
    locality: null,
    scheduledArrivalAt: null,
    scheduledDepartureAt: '2026-09-18T01:30:00Z',
    points: [{ pointId: 'p1', name: 'RTC Complex', pointType: 'BOARDING', address: null }]
  },
  destination: {
    tripStopId: 'stop-dest',
    locationId: 'loc-dest',
    sequenceNumber: 8,
    city: 'Hyderabad',
    state: 'Telangana',
    locality: null,
    scheduledArrivalAt: '2026-09-18T07:30:00Z',
    scheduledDepartureAt: null,
    points: [{ pointId: 'p2', name: 'MGBS', pointType: 'DROPPING', address: null }]
  },
  ...overrides
});

export const bookingFixture = (overrides: Partial<Booking> = {}): Booking => ({
  bookingId: 'booking-1',
  bookingReference: 'BB-1001',
  tripId: 'trip-1',
  holdId: 'hold-1',
  status: 'PENDING_PAYMENT',
  originSequence: 1,
  destinationSequence: 8,
  originTripStopId: 'stop-origin',
  destinationTripStopId: 'stop-dest',
  currency: 'INR',
  baseAmount: 1299,
  taxAmount: 0,
  feeAmount: 0,
  discountAmount: 0,
  totalAmount: 1299,
  createdAt: '2026-09-17T10:00:00Z',
  paymentExpiresAt: '2026-09-17T10:15:00Z',
  items: [
    {
      bookingItemId: 'item-1',
      seatInventoryId: 'inv-1',
      passengerId: 'pax-1',
      seatNumber: 'U1',
      seatType: 'SLEEPER',
      originSequence: 1,
      destinationSequence: 8,
      baseAmount: 1299,
      totalAmount: 1299,
      status: 'ACTIVE'
    }
  ],
  passengers: [{ passengerId: 'pax-1', fullName: 'Asha Rao', age: 32, gender: 'FEMALE' }],
  trip: {
    tripId: 'trip-1',
    serviceDate: '2026-09-18',
    timeZone: 'Asia/Kolkata',
    scheduledDepartureAt: '2026-09-18T01:30:00Z',
    scheduledArrivalAt: '2026-09-18T07:30:00Z',
    status: 'ON_SALE',
    operatorId: 'op-1',
    operatorName: 'Coastal Travels',
    busId: 'bus-1',
    busRegistrationNumber: 'AP31AB1234',
    busDisplayName: 'Coastal Sleeper',
    routeId: 'route-1',
    routeCode: 'VSKP-HYD',
    routeName: 'Visakhapatnam to Hyderabad',
    origin: {
      tripStopId: 'stop-origin',
      locationId: 'loc-origin',
      sequenceNumber: 1,
      city: 'Visakhapatnam',
      state: 'Andhra Pradesh',
      locality: null,
      scheduledArrivalAt: null,
      scheduledDepartureAt: '2026-09-18T01:30:00Z',
      points: []
    },
    destination: {
      tripStopId: 'stop-dest',
      locationId: 'loc-dest',
      sequenceNumber: 8,
      city: 'Hyderabad',
      state: 'Telangana',
      locality: null,
      scheduledArrivalAt: '2026-09-18T07:30:00Z',
      scheduledDepartureAt: null,
      points: []
    }
  },
  paymentAttemptId: null,
  paymentStatus: null,
  ticketId: null,
  ticketNumber: null,
  ticketStatus: null,
  latestRefundStatus: null,
  latestRefundAmount: null,
  ...overrides
});
