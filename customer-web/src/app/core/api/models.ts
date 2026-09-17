export interface ApiErrorBody {
  timestamp?: string;
  status?: number;
  error?: string;
  message?: string;
  path?: string;
  fieldViolations?: Array<{ field: string; message: string }>;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  refreshToken: string;
}

export interface RegisterCustomerRequest {
  firstName: string;
  lastName?: string | null;
  email: string;
  password: string;
}

export interface RefreshTokenRequest {
  refreshToken: string;
}

export type UserStatus = 'ACTIVE' | 'SUSPENDED' | 'INACTIVE';

export interface CustomerIdentity {
  userId: string;
  firstName: string;
  lastName: string | null;
  email: string;
  roles: string[];
  status: UserStatus;
}

export interface CustomerLocation {
  id: string;
  city: string;
  state: string;
  countryCode: string | null;
  locality: string | null;
}

export type PointType = 'BOARDING' | 'DROPPING' | 'BOTH';

export interface TripSearchPoint {
  pointId: string;
  name: string;
  pointType: PointType;
  address: string | null;
}

export interface TripSearchStop {
  tripStopId: string;
  locationId: string;
  sequenceNumber: number;
  city: string;
  state: string;
  locality: string | null;
  scheduledArrivalAt: string | null;
  scheduledDepartureAt: string | null;
  points: TripSearchPoint[];
}

export type TripStatus =
  | 'DRAFT'
  | 'SCHEDULED'
  | 'ON_SALE'
  | 'CLOSED'
  | 'DEPARTED'
  | 'COMPLETED'
  | 'CANCELLED';

export interface TripSearchResult {
  tripId: string;
  serviceDate: string;
  timeZone: string;
  scheduledDepartureAt: string;
  scheduledArrivalAt: string;
  status: TripStatus;
  operatorId: string;
  operatorName: string;
  busId: string;
  busRegistrationNumber: string;
  busDisplayName: string | null;
  routeId: string;
  routeCode: string;
  routeName: string;
  baseFare: number;
  currency: string;
  availableSeatCount: number;
  origin: TripSearchStop;
  destination: TripSearchStop;
}

export type TripSeatInventoryStatus = 'AVAILABLE' | 'BLOCKED';

export type JourneySeatAvailability = 'AVAILABLE' | 'UNAVAILABLE';

export interface TripSeatAvailabilitySeat {
  inventoryId: string;
  seatNumber: string;
  seatType: string;
  deck: number;
  row: number;
  column: number;
  physicalStatus: TripSeatInventoryStatus;
  availability: JourneySeatAvailability;
}

export interface TripSeatAvailability {
  tripId: string;
  originStopId: string;
  destinationStopId: string;
  originSequence: number;
  destinationSequence: number;
  seats: TripSeatAvailabilitySeat[];
}

export interface CreateSeatHoldRequest {
  originStopId: string;
  destinationStopId: string;
  seatInventoryIds: string[];
  idempotencyKey?: string | null;
}

export type SeatHoldStatus = 'ACTIVE' | 'CONSUMED' | 'EXPIRED' | 'CANCELLED';

export interface SeatHold {
  holdId: string;
  tripId: string;
  originStopId: string;
  destinationStopId: string;
  originSequence: number;
  destinationSequence: number;
  status: SeatHoldStatus;
  expiresAt: string;
  seatInventoryIds: string[];
}

export interface BookingPassengerInput {
  seatInventoryId: string;
  fullName: string;
  age?: number | null;
  gender?: string | null;
}

export interface CreateBookingRequest {
  holdId: string;
  originStopId: string;
  destinationStopId: string;
  idempotencyKey: string;
  passengers: BookingPassengerInput[];
}

export interface PaymentInitiation {
  paymentAttemptId: string;
  bookingId: string;
  provider: string;
  merchantReference: string;
  providerOrderId: string | null;
  checkoutReference: string | null;
  amount: number;
  currency: string;
  status: PaymentStatus;
  disposition: PaymentDisposition;
  paymentExpiresAt: string | null;
}

export interface RazorpayCheckoutVerificationRequest {
  razorpayPaymentId: string;
  razorpayOrderId: string;
  razorpaySignature: string;
}

export type BookingStatus =
  | 'INITIATED'
  | 'PENDING_PAYMENT'
  | 'CONFIRMED'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'REFUND_PENDING'
  | 'REFUNDED';

export type PaymentStatus =
  | 'INITIATING'
  | 'PENDING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'EXPIRED';

export type TicketStatus = 'ACTIVE' | 'CANCELLED';

export type RefundStatus =
  | 'REQUESTED'
  | 'PROCESSING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED';

export type BookingItemStatus = 'ACTIVE' | 'CANCELLED' | 'REFUNDED' | 'EXPIRED';

export type PaymentDisposition = 'UNAPPLIED' | 'APPLIED_TO_BOOKING' | 'REQUIRES_RESOLUTION';

export interface BookingTripPoint {
  pointId: string;
  name: string;
  pointType: PointType;
  address: string | null;
}

export interface BookingTripStop {
  tripStopId: string;
  locationId: string;
  sequenceNumber: number;
  city: string;
  state: string;
  locality: string | null;
  scheduledArrivalAt: string | null;
  scheduledDepartureAt: string | null;
  points: BookingTripPoint[];
}

export interface BookingTrip {
  tripId: string;
  serviceDate: string;
  timeZone: string;
  scheduledDepartureAt: string;
  scheduledArrivalAt: string;
  status: TripStatus;
  operatorId: string;
  operatorName: string;
  busId: string;
  busRegistrationNumber: string;
  busDisplayName: string | null;
  routeId: string;
  routeCode: string;
  routeName: string;
  origin: BookingTripStop;
  destination: BookingTripStop;
}

export interface BookingItem {
  bookingItemId: string;
  seatInventoryId: string;
  passengerId: string | null;
  seatNumber: string;
  seatType: string;
  originSequence: number;
  destinationSequence: number;
  baseAmount: number;
  totalAmount: number;
  status: BookingItemStatus;
}

export interface BookingPassenger {
  passengerId: string;
  fullName: string;
  age: number | null;
  gender: string | null;
}

export interface Booking {
  bookingId: string;
  bookingReference: string;
  tripId: string;
  holdId: string;
  status: BookingStatus;
  originSequence: number;
  destinationSequence: number;
  originTripStopId: string;
  destinationTripStopId: string;
  currency: string;
  baseAmount: number;
  taxAmount: number;
  feeAmount: number;
  discountAmount: number;
  totalAmount: number;
  createdAt: string;
  paymentExpiresAt: string | null;
  items: BookingItem[];
  passengers: BookingPassenger[];
  trip: BookingTrip;
  paymentAttemptId: string | null;
  paymentStatus: PaymentStatus | null;
  ticketId: string | null;
  ticketNumber: string | null;
  ticketStatus: TicketStatus | null;
  latestRefundStatus: RefundStatus | null;
  latestRefundAmount: number | null;
}

export interface CancelBookingRequest {
  reason?: string | null;
}

export interface BookingCancellation {
  cancellationId: string;
  bookingId: string;
  previousStatus: BookingStatus;
  status: string;
  reason: string | null;
  policyCode: string;
  refundableAmount: number;
  currency: string;
  cancelledAt: string;
  booking: Booking;
}

export interface PaymentAttempt {
  paymentAttemptId: string;
  bookingId: string;
  provider: string;
  merchantReference: string;
  providerOrderId: string | null;
  requestedAmount: number;
  capturedAmount: number | null;
  currency: string;
  status: PaymentStatus;
  disposition: PaymentDisposition;
  createdAt: string;
  processedAt: string | null;
}

export interface Refund {
  refundId: string;
  paymentAttemptId: string;
  bookingId: string;
  provider: string;
  providerRefundId: string | null;
  amount: number;
  currency: string;
  reason: string;
  status: RefundStatus;
  requestedAt: string;
  processedAt: string | null;
}

export interface TicketOperator {
  name: string;
}

export interface TicketJourney {
  origin: string;
  destination: string;
  departure: string;
  arrival: string;
}

export interface TicketPassenger {
  name: string;
  age: number | null;
  gender: string | null;
  seat: string;
  fareAmount: number;
  currency: string;
}

export interface Ticket {
  ticketId: string;
  ticketNumber: string;
  status: TicketStatus;
  issuedAt: string;
  bookingReference: string;
  bookingId: string;
  operator: TicketOperator;
  journey: TicketJourney;
  passengers: TicketPassenger[];
  amount: number;
  currency: string;
}
