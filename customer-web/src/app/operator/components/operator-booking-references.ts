import { BookingItemStatus, BookingStatus } from '../../core/api/models';
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
 * query filtering, sorting, payment status, ticket number/status, QR, check-in,
 * boarding state, customer contact, or booking mutation endpoint.
 * Ticket APIs exist only on the customer namespace and must not be called here.
 * Reads are allowed for OPERATOR_ADMIN and OPERATOR_STAFF.
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

export const BOOKING_ITEM_STATUSES: BookingItemStatus[] = [
  'ACTIVE',
  'CANCELLED',
  'REFUNDED',
  'EXPIRED'
];

export const OPERATOR_TICKET_UNAVAILABLE_NOTE =
  'Ticket status is not available in the operator booking data.';

export type OperatorManifestGroupBy = 'seat' | 'segment' | 'bookingStatus';

export interface OperatorStopOption {
  key: string;
  sequence: number;
  label: string;
}

export interface OperatorManifestGroup {
  key: string;
  heading: string;
  rows: OperatorPassengerManifestRow[];
}

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
  return rows.sort((left, right) => compareManifestRows(left, right));
}

export function isConfirmedBookingStatus(status: BookingStatus): boolean {
  return status === 'CONFIRMED';
}

export function bookingConfirmationLabel(status: BookingStatus): string {
  return status === 'CONFIRMED' ? 'Confirmed booking' : 'Not confirmed';
}

export function uniqueOriginOptions(
  rows: OperatorPassengerManifestRow[]
): OperatorStopOption[] {
  return uniqueStopOptions(rows, (row) => ({
    sequence: row.originSequence,
    label: row.originLabel
  }));
}

export function uniqueDestinationOptions(
  rows: OperatorPassengerManifestRow[]
): OperatorStopOption[] {
  return uniqueStopOptions(rows, (row) => ({
    sequence: row.destinationSequence,
    label: row.destinationLabel
  }));
}

export function groupPassengerManifestRows(
  rows: OperatorPassengerManifestRow[],
  groupBy: OperatorManifestGroupBy
): OperatorManifestGroup[] {
  const groups = new Map<string, OperatorManifestGroup>();
  for (const row of rows) {
    const key =
      groupBy === 'seat'
        ? row.seatNumber
        : groupBy === 'segment'
          ? `${row.originSequence}-${row.destinationSequence}`
          : row.bookingStatus;
    const heading =
      groupBy === 'seat'
        ? `Seat ${row.seatNumber}`
        : groupBy === 'segment'
          ? `${row.originLabel} → ${row.destinationLabel}`
          : row.bookingStatus;
    const existing = groups.get(key);
    if (existing) {
      existing.rows.push(row);
    } else {
      groups.set(key, { key, heading, rows: [row] });
    }
  }

  const ordered = [...groups.values()].sort((left, right) => {
    if (groupBy === 'bookingStatus') {
      return (
        BOOKING_STATUSES.indexOf(left.key as BookingStatus) -
        BOOKING_STATUSES.indexOf(right.key as BookingStatus)
      );
    }
    if (groupBy === 'seat') {
      return left.key.localeCompare(right.key, undefined, { numeric: true });
    }
    const leftSeq = Number(left.key.split('-')[0] ?? '0');
    const rightSeq = Number(right.key.split('-')[0] ?? '0');
    if (leftSeq !== rightSeq) {
      return leftSeq - rightSeq;
    }
    return left.heading.localeCompare(right.heading);
  });

  for (const group of ordered) {
    group.rows = [...group.rows].sort(compareManifestRows);
  }
  return ordered;
}

function uniqueStopOptions(
  rows: OperatorPassengerManifestRow[],
  pick: (row: OperatorPassengerManifestRow) => { sequence: number; label: string }
): OperatorStopOption[] {
  const options = new Map<string, OperatorStopOption>();
  for (const row of rows) {
    const stop = pick(row);
    const key = `${stop.sequence}:${stop.label}`;
    if (!options.has(key)) {
      options.set(key, { key, sequence: stop.sequence, label: stop.label });
    }
  }
  return [...options.values()].sort(
    (left, right) => left.sequence - right.sequence || left.label.localeCompare(right.label)
  );
}

function compareManifestRows(
  left: OperatorPassengerManifestRow,
  right: OperatorPassengerManifestRow
): number {
  const seat = left.seatNumber.localeCompare(right.seatNumber, undefined, { numeric: true });
  if (seat !== 0) {
    return seat;
  }
  if (left.originSequence !== right.originSequence) {
    return left.originSequence - right.originSequence;
  }
  if (left.destinationSequence !== right.destinationSequence) {
    return left.destinationSequence - right.destinationSequence;
  }
  return left.bookingReference.localeCompare(right.bookingReference);
}
