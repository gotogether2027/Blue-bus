import { Component, EventEmitter, Input, Output } from '@angular/core';
import {
  LayoutDraft,
  LayoutDraftSeat,
  anchorAt,
  cellKey,
  isSleeperType
} from './seat-layout-draft';
import { OperatorSeatLayoutMarker } from '../models/operator.models';

@Component({
  selector: 'app-seat-layout-canvas',
  templateUrl: './seat-layout-canvas.component.html',
  styleUrl: './seat-layout-canvas.component.scss'
})
export class SeatLayoutCanvasComponent {
  @Input({ required: true }) draft!: LayoutDraft;
  @Input() deck = 1;
  @Input() readonly = false;
  @Input() selectedKey = '';
  @Output() cellSelected = new EventEmitter<{ deck: number; row: number; column: number }>();

  rows(): number[] {
    return Array.from({ length: this.draft.rowCount }, (_, index) => index + 1);
  }

  columns(): number[] {
    return Array.from({ length: this.draft.columnCount }, (_, index) => index + 1);
  }

  visibleCells(): CanvasCell[] {
    const hidden = new Set<string>();
    for (const seat of this.draft.seats.filter((item) => item.deckNumber === this.deck)) {
      for (let row = seat.rowNumber; row < seat.rowNumber + seat.spanRows; row++) {
        for (let column = seat.columnNumber; column < seat.columnNumber + seat.spanColumns; column++) {
          if (row !== seat.rowNumber || column !== seat.columnNumber) {
            hidden.add(cellKey(this.deck, row, column));
          }
        }
      }
    }
    const cells: CanvasCell[] = [];
    for (const row of this.rows()) {
      for (const column of this.columns()) {
        if (hidden.has(cellKey(this.deck, row, column))) {
          continue;
        }
        const seat = this.seatAt(row, column);
        if (seat) {
          cells.push({ kind: 'seat', row, column, seat });
          continue;
        }
        const marker = this.markerAt(row, column);
        if (marker) {
          cells.push({ kind: 'marker', row, column, marker });
          continue;
        }
        cells.push({ kind: 'empty', row, column });
      }
    }
    return cells;
  }

  seatAt(row: number, column: number): LayoutDraftSeat | null {
    return anchorAt(this.draft, this.deck, row, column);
  }

  markerAt(row: number, column: number): OperatorSeatLayoutMarker | null {
    if (this.seatAt(row, column)) {
      return null;
    }
    return (
      this.draft.markers.find(
        (marker) =>
          marker.deckNumber === this.deck && marker.rowNumber === row && marker.columnNumber === column
      ) ?? null
    );
  }

  label(row: number, column: number): string {
    const seat = this.seatAt(row, column);
    if (seat) {
      const kind = isSleeperType(seat.seatType) ? 'berth' : 'seat';
      const sale = seat.sellable ? 'sellable' : 'not sellable';
      return `${seat.seatNumber} ${kind}, ${seat.seatType}, ${sale}`;
    }
    const marker = this.markerAt(row, column);
    if (marker) {
      return `${marker.type} at row ${row} column ${column}`;
    }
    return `Empty position row ${row} column ${column}`;
  }

  selected(row: number, column: number): boolean {
    const seat = this.seatAt(row, column);
    const key = seat
      ? cellKey(seat.deckNumber, seat.rowNumber, seat.columnNumber)
      : cellKey(this.deck, row, column);
    return key === this.selectedKey;
  }

  choose(row: number, column: number): void {
    if (this.readonly) {
      return;
    }
    const seat = this.seatAt(row, column);
    this.cellSelected.emit({
      deck: this.deck,
      row: seat?.rowNumber ?? row,
      column: seat?.columnNumber ?? column
    });
  }

  sleeper(seat: LayoutDraftSeat): boolean {
    return isSleeperType(seat.seatType);
  }
}

type CanvasCell =
  | { kind: 'seat'; row: number; column: number; seat: LayoutDraftSeat }
  | { kind: 'marker'; row: number; column: number; marker: OperatorSeatLayoutMarker }
  | { kind: 'empty'; row: number; column: number };
