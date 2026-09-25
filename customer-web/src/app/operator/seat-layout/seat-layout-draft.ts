import {
  CreateOperatorSeatLayoutRequest,
  OperatorSeatLayout,
  OperatorSeatLayoutMarker,
  OperatorSeatLayoutType,
  OperatorSeatMarkerType,
  OperatorSeatOrientation,
  OperatorSeatType
} from '../models/operator.models';

export interface LayoutDraftSeat {
  seatNumber: string;
  seatType: string;
  deckNumber: number;
  rowNumber: number;
  columnNumber: number;
  sellable: boolean;
  orientation: OperatorSeatOrientation;
  spanRows: number;
  spanColumns: number;
}

export interface LayoutDraft {
  name: string;
  layoutType: OperatorSeatLayoutType;
  version: number;
  deckCount: number;
  rowCount: number;
  columnCount: number;
  seats: LayoutDraftSeat[];
  markers: OperatorSeatLayoutMarker[];
}

export interface LayoutSummary {
  total: number;
  seaters: number;
  sleepers: number;
  sellable: number;
  nonSellable: number;
  lowerDeck: number;
  upperDeck: number;
}

export type LayoutTool = OperatorSeatType | OperatorSeatMarkerType | 'ERASE';

const SEAT_TYPES = new Set<string>(['SEATER', 'SLEEPER', 'SLEEPER_LOWER', 'SLEEPER_UPPER', 'BERTH']);

export function cellKey(deck: number, row: number, column: number): string {
  return `${deck}:${row}:${column}`;
}

export function isSleeperType(seatType: string): boolean {
  return seatType !== 'SEATER';
}

export function occupiedCells(draft: LayoutDraft): Map<string, LayoutDraftSeat | OperatorSeatLayoutMarker> {
  const cells = new Map<string, LayoutDraftSeat | OperatorSeatLayoutMarker>();
  for (const seat of draft.seats) {
    for (let row = seat.rowNumber; row < seat.rowNumber + seat.spanRows; row++) {
      for (let column = seat.columnNumber; column < seat.columnNumber + seat.spanColumns; column++) {
        cells.set(cellKey(seat.deckNumber, row, column), seat);
      }
    }
  }
  for (const marker of draft.markers) {
    const key = cellKey(marker.deckNumber, marker.rowNumber, marker.columnNumber);
    if (!cells.has(key)) {
      cells.set(key, marker);
    }
  }
  return cells;
}

export function anchorAt(draft: LayoutDraft, deck: number, row: number, column: number): LayoutDraftSeat | null {
  return (
    draft.seats.find(
      (seat) =>
        seat.deckNumber === deck &&
        row >= seat.rowNumber &&
        row < seat.rowNumber + seat.spanRows &&
        column >= seat.columnNumber &&
        column < seat.columnNumber + seat.spanColumns
    ) ?? null
  );
}

export function summaryOf(draft: LayoutDraft): LayoutSummary {
  const sleepers = draft.seats.filter((seat) => isSleeperType(seat.seatType)).length;
  return {
    total: draft.seats.length,
    seaters: draft.seats.length - sleepers,
    sleepers,
    sellable: draft.seats.filter((seat) => seat.sellable).length,
    nonSellable: draft.seats.filter((seat) => !seat.sellable).length,
    lowerDeck: draft.seats.filter((seat) => seat.deckNumber === 1).length,
    upperDeck: draft.seats.filter((seat) => seat.deckNumber > 1).length
  };
}

export function validateDraft(draft: LayoutDraft, options?: { requireSellable?: boolean }): string[] {
  const errors: string[] = [];
  if (!draft.name.trim()) {
    errors.push('Layout name is required.');
  }
  if (draft.deckCount < 1 || draft.rowCount < 1 || draft.columnCount < 1) {
    errors.push('Layout dimensions must be positive.');
  }
  if (draft.seats.length < 1) {
    errors.push('Layout must contain at least one seat.');
  }
  const numbers = new Set<string>();
  const occupied = new Set<string>();
  for (const seat of draft.seats) {
    const number = seat.seatNumber.trim();
    if (!number) {
      errors.push('Seat number is required.');
    } else if (numbers.has(number.toLocaleLowerCase())) {
      errors.push('Seat number already exists.');
    } else {
      numbers.add(number.toLocaleLowerCase());
    }
    if (!SEAT_TYPES.has(seat.seatType)) {
      errors.push('Invalid seat type.');
    }
    if (seat.spanRows < 1 || seat.spanColumns < 1) {
      errors.push('Seat span must be positive.');
    }
    if (seat.deckNumber < 1 || seat.deckNumber > draft.deckCount) {
      errors.push('Seat deck is outside the layout.');
    }
    for (let row = seat.rowNumber; row < seat.rowNumber + Math.max(seat.spanRows, 1); row++) {
      for (let column = seat.columnNumber; column < seat.columnNumber + Math.max(seat.spanColumns, 1); column++) {
        if (row < 1 || column < 1 || row > draft.rowCount || column > draft.columnCount) {
          errors.push('Seat span is outside the layout dimensions.');
          continue;
        }
        const key = cellKey(seat.deckNumber, row, column);
        if (occupied.has(key)) {
          errors.push('Seat positions overlap.');
        }
        occupied.add(key);
      }
    }
  }
  for (const marker of draft.markers) {
    if (
      marker.deckNumber < 1 ||
      marker.rowNumber < 1 ||
      marker.columnNumber < 1 ||
      marker.deckNumber > draft.deckCount ||
      marker.rowNumber > draft.rowCount ||
      marker.columnNumber > draft.columnCount
    ) {
      errors.push('Marker position is outside the layout dimensions.');
      continue;
    }
    const key = cellKey(marker.deckNumber, marker.rowNumber, marker.columnNumber);
    if (occupied.has(key)) {
      errors.push('Seat positions overlap.');
    }
    occupied.add(key);
  }
  if (options?.requireSellable && !draft.seats.some((seat) => seat.sellable)) {
    errors.push('Cannot publish an empty layout.');
  }
  return [...new Set(errors)];
}

export function autoNumber(draft: LayoutDraft): LayoutDraft {
  const seats = [...draft.seats].sort(comparePosition);
  const ordinals = rowOrdinals(seats);
  const grouped = new Map<string, LayoutDraftSeat[]>();
  for (const seat of seats) {
    const key = `${seat.deckNumber}:${ordinals.get(positionKey(seat))}`;
    grouped.set(key, [...(grouped.get(key) ?? []), seat]);
  }
  const numbered = seats.map((seat) => {
    const key = `${seat.deckNumber}:${ordinals.get(positionKey(seat))}`;
    const group = grouped.get(key) ?? [seat];
    const index = group.findIndex((candidate) => candidate === seat);
    const rowLabel = String(ordinals.get(positionKey(seat)) ?? seat.rowNumber).padStart(2, '0');
    const letter = String.fromCharCode(65 + index);
    const suffix = numberSuffix(seat, draft.layoutType);
    const sameSuffix = group.filter((candidate) => numberSuffix(candidate, draft.layoutType) === suffix);
    let seatNumber = `${rowLabel}${letter}`;
    if (suffix && sameSuffix.length === 1) {
      seatNumber = `${rowLabel}${suffix}`;
    } else if (suffix) {
      seatNumber = `${rowLabel}${letter}${suffix}`;
    }
    return { ...seat, seatNumber };
  });
  return { ...draft, seats: numbered };
}

export function placeTool(
  draft: LayoutDraft,
  tool: LayoutTool,
  deck: number,
  row: number,
  column: number
): LayoutDraft {
  const without = clearCell(draft, deck, row, column);
  if (tool === 'ERASE') {
    return without;
  }
  if (isMarkerTool(tool)) {
    return {
      ...without,
      markers: [
        ...without.markers,
        { type: tool, deckNumber: deck, rowNumber: row, columnNumber: column }
      ]
    };
  }
  const spanRows = tool === 'SEATER' ? 1 : 2;
  const spanColumns = 1;
  return autoNumber({
    ...without,
    seats: [
      ...without.seats,
      {
        seatNumber: 'TMP',
        seatType: tool,
        deckNumber: deck,
        rowNumber: row,
        columnNumber: column,
        sellable: true,
        orientation: tool === 'SEATER' ? 'FORWARD' : 'HORIZONTAL',
        spanRows,
        spanColumns
      }
    ]
  });
}

export function updateSeat(draft: LayoutDraft, originalNumber: string, next: LayoutDraftSeat): LayoutDraft {
  return {
    ...draft,
    seats: draft.seats.map((seat) => (seat.seatNumber === originalNumber ? next : seat))
  };
}

export function removeSeat(draft: LayoutDraft, seatNumber: string): LayoutDraft {
  return { ...draft, seats: draft.seats.filter((seat) => seat.seatNumber !== seatNumber) };
}

function clearCell(draft: LayoutDraft, deck: number, row: number, column: number): LayoutDraft {
  const anchor = anchorAt(draft, deck, row, column);
  return {
    ...draft,
    seats: anchor ? draft.seats.filter((seat) => seat !== anchor) : draft.seats,
    markers: draft.markers.filter(
      (marker) =>
        !(marker.deckNumber === deck && marker.rowNumber === row && marker.columnNumber === column)
    )
  };
}

function isMarkerTool(tool: LayoutTool): tool is OperatorSeatMarkerType {
  return (
    tool === 'AISLE' ||
    tool === 'EMPTY' ||
    tool === 'DOOR' ||
    tool === 'DRIVER' ||
    tool === 'TOILET' ||
    tool === 'UTILITY' ||
    tool === 'BLOCKED'
  );
}

function numberSuffix(seat: LayoutDraftSeat, layoutType: OperatorSeatLayoutType): 'L' | 'U' | '' {
  if (seat.seatType === 'SEATER') {
    return '';
  }
  if (seat.seatType === 'SLEEPER_UPPER' || (layoutType === 'SLEEPER' && seat.deckNumber > 1)) {
    return 'U';
  }
  if (
    seat.seatType === 'SLEEPER_LOWER' ||
    seat.seatType === 'SLEEPER' ||
    seat.seatType === 'BERTH' ||
    seat.seatType === 'SLEEPER_UPPER'
  ) {
    return seat.seatType === 'SLEEPER_UPPER' ? 'U' : 'L';
  }
  return '';
}

function rowOrdinals(seats: LayoutDraftSeat[]): Map<string, number> {
  const byDeck = new Map<number, number[]>();
  for (const seat of seats) {
    const rows = byDeck.get(seat.deckNumber) ?? [];
    if (!rows.includes(seat.rowNumber)) {
      rows.push(seat.rowNumber);
    }
    byDeck.set(seat.deckNumber, rows);
  }
  const ordinals = new Map<string, number>();
  for (const [deck, rows] of byDeck) {
    rows.sort((left, right) => left - right);
    rows.forEach((row, index) => ordinals.set(`${deck}:${row}`, index + 1));
  }
  return ordinals;
}

function positionKey(seat: LayoutDraftSeat): string {
  return `${seat.deckNumber}:${seat.rowNumber}`;
}

function comparePosition(left: LayoutDraftSeat, right: LayoutDraftSeat): number {
  return (
    left.deckNumber - right.deckNumber ||
    left.rowNumber - right.rowNumber ||
    left.columnNumber - right.columnNumber
  );
}

export function draftFromLayout(layout: OperatorSeatLayout): LayoutDraft {
  return {
    name: layout.name,
    layoutType: layout.layoutType ?? 'CUSTOM',
    version: layout.version,
    deckCount: layout.deckCount,
    rowCount: layout.rowCount,
    columnCount: layout.columnCount,
    markers: [...(layout.markers ?? [])],
    seats: layout.seats.map((seat) => ({
      seatNumber: seat.seatNumber,
      seatType: seat.seatType,
      deckNumber: seat.deckNumber,
      rowNumber: seat.rowNumber,
      columnNumber: seat.columnNumber,
      sellable: seat.sellable,
      orientation: seat.orientation ?? 'FORWARD',
      spanRows: seat.spanRows ?? 1,
      spanColumns: seat.spanColumns ?? 1
    }))
  };
}

export function toCreateRequest(draft: LayoutDraft): CreateOperatorSeatLayoutRequest {
  return {
    name: draft.name.trim(),
    version: Number(draft.version),
    layoutType: draft.layoutType,
    deckCount: Number(draft.deckCount),
    rowCount: Number(draft.rowCount),
    columnCount: Number(draft.columnCount),
    markers: draft.markers,
    seats: draft.seats.map((seat) => ({
      seatNumber: seat.seatNumber.trim(),
      seatType: seat.seatType,
      deckNumber: Number(seat.deckNumber),
      rowNumber: Number(seat.rowNumber),
      columnNumber: Number(seat.columnNumber),
      sellable: seat.sellable,
      orientation: seat.orientation,
      spanRows: Number(seat.spanRows),
      spanColumns: Number(seat.spanColumns)
    }))
  };
}
