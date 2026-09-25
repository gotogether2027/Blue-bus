import { OperatorSeatLayoutMarker, OperatorSeatLayoutType, OperatorSeatType } from '../models/operator.models';
import { LayoutDraft, LayoutDraftSeat, autoNumber } from './seat-layout-draft';

export interface SeatLayoutTemplate {
  id: string;
  label: string;
  description: string;
  build: () => LayoutDraft;
}

export const SEAT_LAYOUT_TEMPLATES: SeatLayoutTemplate[] = [
  { id: 'seater-2-2-32', label: '2+2 Seater — 32 seats', description: 'Eight rows, aisle in the centre.', build: () => seater('2+2 Seater 32', 'SEATER', 8, [1, 2, 4, 5], 5) },
  { id: 'seater-2-2-40', label: '2+2 Seater — 40 seats', description: 'Ten rows, aisle in the centre.', build: () => seater('2+2 Seater 40', 'SEATER', 10, [1, 2, 4, 5], 5) },
  { id: 'seater-2-1-30', label: '2+1 Seater — 30 seats', description: 'Ten rows with a single seat beside the aisle.', build: () => seater('2+1 Seater 30', 'SEATER', 10, [1, 2, 4], 4) },
  { id: 'seater-2-1-36', label: '2+1 Seater — 36 seats', description: 'Twelve rows with a single seat beside the aisle.', build: () => seater('2+1 Seater 36', 'SEATER', 12, [1, 2, 4], 4) },
  { id: 'sleeper-2-2', label: '2+2 Sleeper', description: 'Tall berths on both sides of the aisle.', build: () => sleeperBands('2+2 Sleeper', 'SLEEPER', 1, 4, [1, 2, 4, 5], 5, 'SLEEPER') },
  { id: 'sleeper-2-1', label: '2+1 Sleeper', description: 'Two berths on the left and one on the right.', build: () => sleeperBands('2+1 Sleeper', 'SLEEPER', 1, 4, [1, 2, 4], 4, 'SLEEPER') },
  { id: 'sleeper-1-1', label: '1+1 Sleeper', description: 'One wide berth on each side of the aisle.', build: () => wideSleeper('1+1 Sleeper') },
  { id: 'mixed-2-1', label: '2+1 Sleeper + Seater', description: 'Seater rows at the front and berths at the rear.', build: () => mixedSingleDeck() },
  { id: 'multi-sleeper', label: 'Multi-deck Sleeper', description: 'Lower and upper berths.', build: () => multiSleeper() },
  { id: 'multi-mixed', label: 'Multi-deck Mixed', description: 'Seaters below and sleepers above.', build: () => multiMixed() },
  { id: 'custom', label: 'Custom', description: 'Empty bus outline you can build cell by cell.', build: () => customTemplate() }
];

function seater(
  name: string,
  layoutType: OperatorSeatLayoutType,
  seatRows: number,
  seatColumns: number[],
  columnCount: number
): LayoutDraft {
  const seats: LayoutDraftSeat[] = [];
  const markers: OperatorSeatLayoutMarker[] = driverRow(1, columnCount);
  const aisleColumn = seatColumns.includes(3) ? 0 : 3;
  for (let index = 0; index < seatRows; index++) {
    const row = index + 2;
    for (const column of seatColumns) {
      seats.push(seat(1, row, column, 'SEATER', 'FORWARD', 1, 1));
    }
    if (aisleColumn > 0 && aisleColumn <= columnCount && !seatColumns.includes(aisleColumn)) {
      markers.push({
        type: index === 3 ? 'DOOR' : 'AISLE',
        deckNumber: 1,
        rowNumber: row,
        columnNumber: aisleColumn
      });
    }
  }
  return autoNumber({
    name,
    layoutType,
    version: 1,
    deckCount: 1,
    rowCount: seatRows + 1,
    columnCount,
    seats,
    markers
  });
}

function sleeperBands(
  name: string,
  layoutType: OperatorSeatLayoutType,
  deck: number,
  bands: number,
  columns: number[],
  columnCount: number,
  seatType: OperatorSeatType
): LayoutDraft {
  const seats: LayoutDraftSeat[] = [];
  const markers: OperatorSeatLayoutMarker[] = driverRow(deck, columnCount);
  for (let band = 0; band < bands; band++) {
    const row = 2 + band * 2;
    for (const column of columns) {
      seats.push(seat(deck, row, column, seatType, 'VERTICAL', 2, 1));
    }
    if (!columns.includes(3)) {
      markers.push({ type: 'AISLE', deckNumber: deck, rowNumber: row, columnNumber: 3 });
      markers.push({ type: 'AISLE', deckNumber: deck, rowNumber: row + 1, columnNumber: 3 });
    }
  }
  return autoNumber({
    name,
    layoutType,
    version: 1,
    deckCount: Math.max(deck, 1),
    rowCount: 1 + bands * 2,
    columnCount,
    seats,
    markers
  });
}

function wideSleeper(name: string): LayoutDraft {
  const seats: LayoutDraftSeat[] = [];
  const markers: OperatorSeatLayoutMarker[] = driverRow(1, 5);
  for (let band = 0; band < 4; band++) {
    const row = 2 + band * 2;
    seats.push(seat(1, row, 1, 'SLEEPER', 'HORIZONTAL', 2, 2));
    seats.push(seat(1, row, 4, 'SLEEPER', 'HORIZONTAL', 2, 2));
    markers.push({ type: 'AISLE', deckNumber: 1, rowNumber: row, columnNumber: 3 });
    markers.push({ type: 'AISLE', deckNumber: 1, rowNumber: row + 1, columnNumber: 3 });
  }
  return autoNumber({
    name,
    layoutType: 'SLEEPER',
    version: 1,
    deckCount: 1,
    rowCount: 9,
    columnCount: 5,
    seats,
    markers
  });
}

const mixedSingleDeck = (): LayoutDraft => {
  const front = seater('2+1 Sleeper + Seater', 'SEATER_SLEEPER', 4, [1, 2, 4], 4);
  const seats = [...front.seats];
  const markers = front.markers.filter((marker) => marker.rowNumber <= 5);
  for (let band = 0; band < 3; band++) {
    const row = 6 + band * 2;
    seats.push(seat(1, row, 1, 'SLEEPER', 'VERTICAL', 2, 1));
    seats.push(seat(1, row, 2, 'SLEEPER', 'VERTICAL', 2, 1));
    seats.push(seat(1, row, 4, 'SLEEPER', 'VERTICAL', 2, 1));
    markers.push({ type: 'AISLE', deckNumber: 1, rowNumber: row, columnNumber: 3 });
    markers.push({ type: 'AISLE', deckNumber: 1, rowNumber: row + 1, columnNumber: 3 });
  }
  return autoNumber({ ...front, rowCount: 11, seats, markers });
};

const multiSleeper = (): LayoutDraft => {
  const lower = sleeperBands('Multi-deck Sleeper', 'SLEEPER', 1, 3, [1, 2, 4], 4, 'SLEEPER_LOWER');
  const upper = sleeperBands('Upper', 'SLEEPER', 2, 3, [1, 2, 4], 4, 'SLEEPER_UPPER');
  return autoNumber({
    ...lower,
    deckCount: 2,
    seats: [...lower.seats, ...upper.seats.map((seat) => ({ ...seat, deckNumber: 2 }))],
    markers: [...lower.markers, ...upper.markers.map((marker) => ({ ...marker, deckNumber: 2 }))]
  });
};

const multiMixed = (): LayoutDraft => {
  const lower = seater('Multi-deck Mixed', 'SEATER_SLEEPER', 4, [1, 2, 4, 5], 5);
  const upper = sleeperBands('Upper', 'SLEEPER', 2, 3, [1, 4], 5, 'SLEEPER_UPPER');
  return autoNumber({
    name: 'Multi-deck Mixed',
    layoutType: 'SEATER_SLEEPER',
    version: 1,
    deckCount: 2,
    rowCount: Math.max(lower.rowCount, upper.rowCount),
    columnCount: 5,
    seats: [...lower.seats, ...upper.seats.map((seat) => ({ ...seat, deckNumber: 2 }))],
    markers: [...lower.markers, ...upper.markers.map((marker) => ({ ...marker, deckNumber: 2 }))]
  });
};

function customTemplate(): LayoutDraft {
  const columnCount = 5;
  const markers = driverRow(1, columnCount);
  for (let row = 2; row <= 8; row++) {
    markers.push({ type: 'AISLE', deckNumber: 1, rowNumber: row, columnNumber: 3 });
  }
  return {
    name: '',
    layoutType: 'CUSTOM',
    version: 1,
    deckCount: 1,
    rowCount: 8,
    columnCount,
    seats: [],
    markers
  };
}

function driverRow(deck: number, columnCount: number): OperatorSeatLayoutMarker[] {
  return Array.from({ length: columnCount }, (_, index) => ({
    type: 'DRIVER' as const,
    deckNumber: deck,
    rowNumber: 1,
    columnNumber: index + 1
  }));
}

function seat(
  deck: number,
  row: number,
  column: number,
  seatType: OperatorSeatType,
  orientation: LayoutDraftSeat['orientation'],
  spanRows: number,
  spanColumns: number
): LayoutDraftSeat {
  return {
    seatNumber: 'TMP',
    seatType,
    deckNumber: deck,
    rowNumber: row,
    columnNumber: column,
    sellable: true,
    orientation,
    spanRows,
    spanColumns
  };
}
