import {
  BookingItemStatus,
  BookingStatus,
  BookingTrip,
  PointType,
  TripSeatInventoryStatus,
  TripStatus
} from '../../core/api/models';

export type OperatorRole = 'OPERATOR_ADMIN' | 'OPERATOR_STAFF';
export type OperatorMemberStatus = 'ACTIVE' | 'INACTIVE';
export type OperatorStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'INACTIVE';
export type OperatorBusStatus = 'ACTIVE' | 'INACTIVE' | 'MAINTENANCE';
export type OperatorSeatLayoutStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type OperatorSeatLayoutType = 'SEATER' | 'SLEEPER' | 'SEATER_SLEEPER' | 'CUSTOM';
export type OperatorSeatOrientation = 'FORWARD' | 'BACKWARD' | 'HORIZONTAL' | 'VERTICAL';
export type OperatorSeatMarkerType =
  | 'AISLE'
  | 'EMPTY'
  | 'DOOR'
  | 'DRIVER'
  | 'TOILET'
  | 'UTILITY'
  | 'BLOCKED';
export type OperatorSeatType = 'SEATER' | 'SLEEPER' | 'SLEEPER_LOWER' | 'SLEEPER_UPPER' | 'BERTH';
export type OperatorStopKind = 'SOURCE' | 'INTERMEDIATE' | 'DESTINATION';
export type OperatorTripStopStatus = 'ACTIVE' | 'SKIPPED' | 'CANCELLED';

export interface OperatorMembership {
  operatorId: string;
  operatorDisplayName: string;
  role: OperatorRole;
}

export interface OperatorMember {
  userId: string;
  email: string;
  firstName: string | null;
  lastName: string | null;
  role: OperatorRole;
  status: OperatorMemberStatus;
}

export interface CreateOperatorMemberRequest {
  userId: string;
  role: OperatorRole;
}

export interface UpdateOperatorMemberRequest {
  role?: OperatorRole;
  status?: OperatorMemberStatus;
}

export interface OperatorProfile {
  id: string;
  legalName: string;
  displayName: string;
  status: OperatorStatus;
  supportEmail: string | null;
  supportPhoneE164: string | null;
}

export interface UpdateOperatorSupportContactRequest {
  supportEmail?: string | null;
  supportPhoneE164?: string | null;
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
  orientation: OperatorSeatOrientation;
  spanRows: number;
  spanColumns: number;
}

export interface OperatorSeatLayoutMarker {
  type: OperatorSeatMarkerType;
  deckNumber: number;
  rowNumber: number;
  columnNumber: number;
}

export interface OperatorSeatLayout {
  id: string;
  operatorId: string;
  name: string;
  version: number;
  layoutType: OperatorSeatLayoutType;
  deckCount: number;
  rowCount: number;
  columnCount: number;
  status: OperatorSeatLayoutStatus;
  seats: OperatorSeatLayoutSeat[];
  markers: OperatorSeatLayoutMarker[];
  updatedAt: string | null;
}

export interface OperatorSeatLayoutSeatRequest {
  seatNumber: string;
  deckNumber: number;
  rowNumber: number;
  columnNumber: number;
  seatType: string;
  sellable: boolean;
  orientation: OperatorSeatOrientation;
  spanRows: number;
  spanColumns: number;
}

export interface CreateOperatorSeatLayoutRequest {
  name: string;
  version: number;
  layoutType: OperatorSeatLayoutType;
  deckCount: number;
  rowCount: number;
  columnCount: number;
  seats: OperatorSeatLayoutSeatRequest[];
  markers: OperatorSeatLayoutMarker[];
}

export interface UpdateOperatorSeatLayoutRequest {
  name?: string;
  layoutType?: OperatorSeatLayoutType;
  deckCount?: number;
  rowCount?: number;
  columnCount?: number;
  seats?: OperatorSeatLayoutSeatRequest[];
  markers?: OperatorSeatLayoutMarker[];
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

export type OperatorRouteStatus = 'ACTIVE' | 'INACTIVE';

export interface OperatorRoutePoint {
  id: string;
  routeStopId: string;
  name: string;
  pointType: PointType;
  address: string | null;
  latitude: number | null;
  longitude: number | null;
  active: boolean;
}

export interface OperatorRouteStop {
  id: string;
  routeId: string;
  locationId: string;
  sequenceNumber: number;
  stopKind: OperatorStopKind;
  arrivalOffsetMinutes: number | null;
  departureOffsetMinutes: number | null;
  distanceKm: number | null;
  points: OperatorRoutePoint[];
}

export interface OperatorRoute {
  id: string;
  operatorId: string;
  code: string;
  name: string;
  sourceLocationId: string;
  destinationLocationId: string;
  status: OperatorRouteStatus;
  stops: OperatorRouteStop[];
}

export interface CreateOperatorRoutePointRequest {
  name: string;
  pointType: PointType;
  address?: string;
  latitude?: number;
  longitude?: number;
  active?: boolean;
}

export interface CreateOperatorRouteStopRequest {
  locationId: string;
  sequenceNumber: number;
  stopKind: OperatorStopKind;
  arrivalOffsetMinutes?: number;
  departureOffsetMinutes?: number;
  distanceKm?: number;
  points?: CreateOperatorRoutePointRequest[];
}

export interface CreateOperatorRouteRequest {
  code: string;
  name: string;
  sourceLocationId: string;
  destinationLocationId: string;
  stops?: CreateOperatorRouteStopRequest[];
}

export interface UpdateOperatorRouteRequest {
  name?: string;
  sourceLocationId?: string;
  destinationLocationId?: string;
}

export interface UpdateOperatorRouteStopRequest {
  locationId: string;
  sequenceNumber: number;
  stopKind: OperatorStopKind;
  arrivalOffsetMinutes?: number;
  departureOffsetMinutes?: number;
  distanceKm?: number;
}

export interface UpdateOperatorRoutePointRequest {
  name: string;
  pointType: PointType;
  address?: string;
  latitude?: number;
  longitude?: number;
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

export interface CreateOperatorTripRequest {
  busId: string;
  routeId: string;
  scheduledDepartureAt: string;
  scheduledArrivalAt: string;
  baseFare: number;
  bookingOpensAt: string;
  bookingClosesAt: string;
  timeZone?: string;
}

export interface UpdateOperatorTripRequest {
  baseFare?: number;
  bookingOpensAt?: string;
  bookingClosesAt?: string;
}

export interface BlockOperatorTripSeatRequest {
  reason: string;
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
