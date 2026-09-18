import { BookingStatus } from '../../core/api/models';
import {
  OperatorBooking,
  OperatorBookingPassenger
} from '../models/operator.models';

/**
 * Operator booking contract actually implemented by the backend:
 * GET /api/v1/operator/{operatorId}/trips/{tripId}/bookings
 * GET /api/v1/operator/{operatorId}/trips/{tripId}/bookings/{bookingId}
 *
 * Both return OperatorBookingResponse (list is an array). There is no pagination,
 * query filtering, sorting, payment status, ticket number/status, customer contact,
 * or booking mutation endpoint. Reads are allowed for OPERATOR_ADMIN and OPERATOR_STAFF.
 */
export const BOOKING_STATUSES: BookingStatus[] = [
  'INITIATED',
  'PENDING_PAYMENT',
  'CONFIRMED',
  'CANCELLED',
  'EXPIRED',
  'REFUND_PENDING',
  'REFUNDED'
];

export interface OperatorPassengerManifestRow {
  key: string;
  bookingId: string;
  bookingReference: string;
  bookingStatus: BookingStatus;
  itemStatus: OperatorBooking['items'][number]['status'];
  seatNumber: string;
  seatType: string;
  passengerName: string | null;
  age: number | null;
  gender: string | null;
  originSequence: number;
  destinationSequence: number;
  originLabel: string;
  destinationLabel: string;
}

export function bookingPassenger(
  booking: OperatorBooking,
  passengerId: string | null
): OperatorBookingPassenger | null {
  if (!passengerId) {
    return null;
  }
  return booking.passengers.find((passenger) => passenger.passengerId === passengerId) ?? null;
}

export function bookingStopLabel(booking: OperatorBooking, sequence: number): string {
  if (booking.trip.origin.sequenceNumber === sequence) {
    return booking.trip.origin.city;
  }
  if (booking.trip.destination.sequenceNumber === sequence) {
    return booking.trip.destination.city;
  }
  return `Seq ${sequence}`;
}

export function bookingBelongsToOperatorTrip(
  booking: OperatorBooking,
  operatorId: string,
  tripId: string
): boolean {
  return (
    booking.tripId === tripId &&
    booking.trip.tripId === tripId &&
    booking.trip.operatorId === operatorId
  );
}

export function passengerManifestRows(
  bookings: OperatorBooking[]
): OperatorPassengerManifestRow[] {
  const rows: OperatorPassengerManifestRow[] = [];
  for (const booking of bookings) {
    for (const item of booking.items) {
      const passenger = bookingPassenger(booking, item.passengerId);
      rows.push({
        key: item.bookingItemId,
        bookingId: booking.bookingId,
        bookingReference: booking.bookingReference,
        bookingStatus: booking.status,
        itemStatus: item.status,
        seatNumber: item.seatNumber,
        seatType: item.seatType,
        passengerName: passenger?.fullName ?? null,
        age: passenger?.age ?? null,
        gender: passenger?.gender ?? null,
        originSequence: item.originSequence,
        destinationSequence: item.destinationSequence,
        originLabel: bookingStopLabel(booking, item.originSequence),
        destinationLabel: bookingStopLabel(booking, item.destinationSequence)
      });
    }
  }
  return rows.sort((left, right) => {
    const seat = left.seatNumber.localeCompare(right.seatNumber, undefined, { numeric: true });
    if (seat !== 0) {
      return seat;
    }
    return left.bookingReference.localeCompare(right.bookingReference);
  });
}
