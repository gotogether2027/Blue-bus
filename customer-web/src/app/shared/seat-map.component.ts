import { Component, EventEmitter, Input, Output } from '@angular/core';
import { TripSeatAvailabilitySeat } from '../core/api/models';
import { SeatDeck, isSeatSelectable } from '../core/api/seats';

@Component({
  selector: 'app-seat-map',
  templateUrl: './seat-map.component.html',
  styleUrl: './seat-map.component.scss'
})
export class SeatMapComponent {
  @Input({ required: true }) decks: SeatDeck[] = [];
  @Input() selectedIds: string[] = [];
  @Output() select = new EventEmitter<TripSeatAvailabilitySeat>();

  activeDeckNumber: number | null = null;
  readonly isSeatSelectable = isSeatSelectable;

  get visibleDeck(): SeatDeck | null {
    return this.decks.find((deck) => deck.deck === this.activeDeckNumber) ?? this.decks[0] ?? null;
  }

  chooseDeck(deck: number): void {
    this.activeDeckNumber = deck;
  }

  deckName(deck: number): string {
    if (deck === 1) {
      return 'Lower deck';
    }
    if (deck === 2) {
      return 'Upper deck';
    }
    return `Deck ${deck}`;
  }

  rowColumns(deck: SeatDeck): string {
    return `1.75rem repeat(${this.columnCount(deck)}, minmax(2.7rem, 1fr))`;
  }

  isBerth(seat: TripSeatAvailabilitySeat): boolean {
    const type = seat.seatType.toUpperCase();
    return type.includes('SLEEPER') || type === 'BERTH';
  }

  labelFor(seat: TripSeatAvailabilitySeat): string {
    if (seat.physicalStatus === 'BLOCKED') {
      return `Seat ${seat.seatNumber} blocked`;
    }
    if (seat.availability === 'UNAVAILABLE') {
      return `Seat ${seat.seatNumber} unavailable`;
    }
    return `Seat ${seat.seatNumber} ${seat.seatType}`;
  }

  private columnCount(deck: SeatDeck): number {
    let max = 1;
    for (const row of deck.rows) {
      for (const seat of row.seats) {
        if (seat.column > max) {
          max = seat.column;
        }
      }
    }
    return max;
  }
}
