import { Component, EventEmitter, Input, Output } from '@angular/core';
import { TripSeatAvailabilitySeat } from '../core/api/models';
import { SeatDeck, isSeatSelectable } from '../core/api/seats';

@Component({
  selector: 'app-seat-map',
  template: `
    @for (deck of decks; track deck.deck) {
      <section class="seat-deck">
        <h3>Deck {{ deck.deck }}</h3>
        <div class="seat-rows">
          @for (row of deck.rows; track row.row) {
            <div class="seat-row">
              <span class="seat-row-label">{{ row.row }}</span>
              @for (seat of row.seats; track seat.inventoryId) {
                <button
                  type="button"
                  class="seat"
                  [class.selected]="selectedIds.includes(seat.inventoryId)"
                  [class.blocked]="seat.physicalStatus === 'BLOCKED'"
                  [class.taken]="seat.physicalStatus === 'AVAILABLE' && seat.availability === 'UNAVAILABLE'"
                  [disabled]="!isSeatSelectable(seat)"
                  [attr.aria-pressed]="selectedIds.includes(seat.inventoryId)"
                  [attr.aria-label]="labelFor(seat)"
                  (click)="select.emit(seat)"
                >
                  {{ seat.seatNumber }}
                </button>
              }
            </div>
          }
        </div>
      </section>
    }
  `
})
export class SeatMapComponent {
  @Input({ required: true }) decks: SeatDeck[] = [];
  @Input() selectedIds: string[] = [];
  @Output() select = new EventEmitter<TripSeatAvailabilitySeat>();

  readonly isSeatSelectable = isSeatSelectable;

  labelFor(seat: TripSeatAvailabilitySeat): string {
    if (seat.physicalStatus === 'BLOCKED') {
      return `Seat ${seat.seatNumber} blocked`;
    }
    if (seat.availability === 'UNAVAILABLE') {
      return `Seat ${seat.seatNumber} unavailable`;
    }
    return `Seat ${seat.seatNumber} ${seat.seatType}`;
  }
}
