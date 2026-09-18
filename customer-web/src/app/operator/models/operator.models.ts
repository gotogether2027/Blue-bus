import {
  BookingItemStatus,
  BookingStatus,
  BookingTrip,
  PointType,
  TripSeatInventoryStatus,
  TripStatus
} from '../../core/api/models';

export type OperatorRole = 'OPERATOR_ADMIN' | 'OPERATOR_STAFF';
export type OperatorStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'INACTIVE';
export type OperatorBusStatus = 'ACTIVE' | 'INACTIVE' | 'MAINTENANCE';
export type OperatorSeatLayoutStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type OperatorStopKind = 'SOURCE' | 'INTERMEDIATE' | 'DESTINATION';
export type OperatorTripStopStatus = 'ACTIVE' | 'SKIPPED' | 'CANCELLED';

export interface OperatorMembership {
  operatorId: string;
  operatorDisplayName: string;
  role: OperatorRole;
}

export interface OperatorProfile {
  id: string;
  legalName: string;
  displayName: string;
  status: OperatorStatus;
  supportEmail: string | null;
  supportPhoneE164: string | null;
}

export interface OperatorBus {
  id: string;
  operatorId: string;
  busTypeId: string;
  seatLayoutId: string;
  registrationNumber: string;
  displayName: string | null;
  status: OperatorBusStatus;
}

export interface OperatorBusType {
  id: string;
  code: string;
  displayName: string;
  active: boolean;
}

export interface OperatorSeatLayoutSeat {
  id: string;
  seatNumber: string;
  deckNumber: number;
  rowNumber: number;
  columnNumber: number;
  seatType: string;
  sellable: boolean;
}

export interface OperatorSeatLayout {
  id: string;
  operatorId: string;
  name: string;
  version: number;
  deckCount: number;
  rowCount: number;
  columnCount: number;
  status: OperatorSeatLayoutStatus;
  seats: OperatorSeatLayoutSeat[];
}

export interface CreateOperatorBusRequest {
  busTypeId: string;
  seatLayoutId: string;
  registrationNumber: string;
  displayName?: string;
}

export interface UpdateOperatorBusRequest {
  displayName?: string;
  busTypeId?: string;
  seatLayoutId?: string;
}

export interface OperatorTripPoint {
  id: string;
  tripStopId: string;
  sourceRoutePointId: string | null;
  name: string;
  pointType: PointType;
  address: string | null;
  latitude: number | null;
  longitude: number | null;
  active: boolean;
}

export interface OperatorTripStop {
  id: string;
  tripId: string;
  routeStopId: string | null;
  locationId: string;
  sequenceNumber: number;
  stopKind: OperatorStopKind;
  stopStatus: OperatorTripStopStatus;
  scheduledArrivalAt: string | null;
  scheduledDepartureAt: string | null;
  distanceKm: number | null;
  points: OperatorTripPoint[];
}

export interface OperatorTripSeatInventory {
  id: string;
  tripId: string;
  layoutSeatId: string;
  seatLayoutId: string;
  seatLayoutVersion: number;
  seatNumber: string;
  seatType: string;
  deckNumber: number;
  rowNumber: number;
  columnNumber: number;
  physicalStatus: TripSeatInventoryStatus;
  blockReason: string | null;
}

export interface OperatorTrip {
  id: string;
  operatorId: string;
  busId: string;
  routeId: string;
  seatLayoutId: string;
  serviceDate: string;
  timeZone: string;
  scheduledDepartureAt: string;
  scheduledArrivalAt: string;
  baseFare: number;
  bookingOpensAt: string;
  bookingClosesAt: string;
  status: TripStatus;
  stops: OperatorTripStop[];
  seatInventory: OperatorTripSeatInventory[];
}

export interface OperatorTripFilters {
  serviceDate?: string;
  status?: TripStatus;
}

export interface OperatorBookingItem {
  bookingItemId: string;
  passengerId: string | null;
  seatNumber: string;
  seatType: string;
  originSequence: number;
  destinationSequence: number;
  status: BookingItemStatus;
}

export interface OperatorBookingPassenger {
  passengerId: string;
  fullName: string;
  age: number | null;
  gender: string | null;
}

export interface OperatorBooking {
  bookingId: string;
  bookingReference: string;
  status: BookingStatus;
  tripId: string;
  originSequence: number;
  destinationSequence: number;
  originTripStopId: string;
  destinationTripStopId: string;
  currency: string;
  totalAmount: number;
  createdAt: string;
  items: OperatorBookingItem[];
  passengers: OperatorBookingPassenger[];
  trip: BookingTrip;
}
