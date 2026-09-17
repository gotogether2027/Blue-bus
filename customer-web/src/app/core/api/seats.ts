import { CreateBookingRequest, CreateSeatHoldRequest, TripSeatAvailabilitySeat } from './models';

export function isSeatSelectable(seat: TripSeatAvailabilitySeat): boolean {
  return seat.physicalStatus === 'AVAILABLE' && seat.availability === 'AVAILABLE';
}

export function toggleSeatSelection(
  selectedIds: string[],
  seat: TripSeatAvailabilitySeat
): string[] {
  if (!isSeatSelectable(seat)) {
    return selectedIds;
  }
  if (selectedIds.includes(seat.inventoryId)) {
    return selectedIds.filter((id) => id !== seat.inventoryId);
  }
  return [...selectedIds, seat.inventoryId];
}

export interface SeatRow {
  row: number;
  seats: TripSeatAvailabilitySeat[];
}

export interface SeatDeck {
  deck: number;
  rows: SeatRow[];
}

export function groupSeatsByLayout(seats: TripSeatAvailabilitySeat[]): SeatDeck[] {
  const byDeck = new Map<number, Map<number, TripSeatAvailabilitySeat[]>>();
  for (const seat of seats) {
    let rows = byDeck.get(seat.deck);
    if (!rows) {
      rows = new Map<number, TripSeatAvailabilitySeat[]>();
      byDeck.set(seat.deck, rows);
    }
    const row = rows.get(seat.row) ?? [];
    row.push(seat);
    rows.set(seat.row, row);
  }
  return [...byDeck.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([deck, rows]) => ({
      deck,
      rows: [...rows.entries()]
        .sort((a, b) => a[0] - b[0])
        .map(([row, rowSeats]) => ({
          row,
          seats: [...rowSeats].sort((a, b) => a.column - b.column)
        }))
    }));
}

export function toCreateHoldRequest(input: {
  originStopId: string;
  destinationStopId: string;
  seatInventoryIds: string[];
  idempotencyKey: string;
}): CreateSeatHoldRequest {
  return {
    originStopId: input.originStopId,
    destinationStopId: input.destinationStopId,
    seatInventoryIds: [...input.seatInventoryIds],
    idempotencyKey: input.idempotencyKey
  };
}

export function toCreateBookingRequest(input: {
  holdId: string;
  originStopId: string;
  destinationStopId: string;
  idempotencyKey: string;
  passengers: Array<{
    seatInventoryId: string;
    fullName: string;
    age: number | null;
    gender: string | null;
  }>;
}): CreateBookingRequest {
  return {
    holdId: input.holdId,
    originStopId: input.originStopId,
    destinationStopId: input.destinationStopId,
    idempotencyKey: input.idempotencyKey,
    passengers: input.passengers.map((passenger) => ({
      seatInventoryId: passenger.seatInventoryId,
      fullName: passenger.fullName.trim(),
      age: passenger.age,
      gender: passenger.gender?.trim() ? passenger.gender.trim() : null
    }))
  };
}

export function validatePassengers(
  seatInventoryIds: string[],
  passengers: Array<{ seatInventoryId: string; fullName: string; age: number | null; gender: string | null }>
): string | null {
  if (passengers.length !== seatInventoryIds.length) {
    return 'Enter one passenger for each selected seat.';
  }
  const seen = new Set<string>();
  for (const passenger of passengers) {
    if (!seatInventoryIds.includes(passenger.seatInventoryId)) {
      return 'Passenger seats must match the held seats.';
    }
    if (seen.has(passenger.seatInventoryId)) {
      return 'Each held seat can only have one passenger.';
    }
    seen.add(passenger.seatInventoryId);
    const name = passenger.fullName.trim();
    if (!name) {
      return 'Passenger full name is required.';
    }
    if (name.length > 120) {
      return 'Passenger full name must be 120 characters or fewer.';
    }
    if (passenger.age !== null && (passenger.age < 0 || passenger.age > 120)) {
      return 'Passenger age must be between 0 and 120.';
    }
    if (passenger.gender && passenger.gender.length > 30) {
      return 'Passenger gender must be 30 characters or fewer.';
    }
  }
  return null;
}
