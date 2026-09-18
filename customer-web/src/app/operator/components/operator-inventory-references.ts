import { OperatorTripSeatInventory } from '../models/operator.models';

export interface InventorySeatRow {
  rowNumber: number;
  seats: OperatorTripSeatInventory[];
}

export interface InventorySeatDeck {
  deckNumber: number;
  rows: InventorySeatRow[];
}

export function groupTripInventory(seats: OperatorTripSeatInventory[]): InventorySeatDeck[] {
  const byDeck = new Map<number, OperatorTripSeatInventory[]>();
  for (const seat of seats) {
    const deckSeats = byDeck.get(seat.deckNumber) ?? [];
    deckSeats.push(seat);
    byDeck.set(seat.deckNumber, deckSeats);
  }

  return [...byDeck.entries()]
    .sort((left, right) => left[0] - right[0])
    .map(([deckNumber, deckSeats]) => {
      const byRow = new Map<number, OperatorTripSeatInventory[]>();
      for (const seat of deckSeats) {
        const rowSeats = byRow.get(seat.rowNumber) ?? [];
        rowSeats.push(seat);
        byRow.set(seat.rowNumber, rowSeats);
      }
      return {
        deckNumber,
        rows: [...byRow.entries()]
          .sort((left, right) => left[0] - right[0])
          .map(([rowNumber, rowSeats]) => ({
            rowNumber,
            seats: [...rowSeats].sort((left, right) => left.columnNumber - right.columnNumber)
          }))
      };
    });
}
