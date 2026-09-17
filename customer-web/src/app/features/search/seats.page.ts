import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { HoldsService } from '../../core/api/holds.service';
import { SeatsService } from '../../core/api/seats.service';
import { TripSeatAvailability, TripSeatAvailabilitySeat } from '../../core/api/models';
import { readApiError } from '../../core/api/api-error';
import { newIdempotencyKey } from '../../core/api/idempotency';
import { SeatDeck, groupSeatsByLayout, toCreateHoldRequest, toggleSeatSelection } from '../../core/api/seats';
import { CheckoutSessionService } from '../../core/checkout/checkout-session.service';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { SeatMapComponent } from '../../shared/seat-map.component';
import { formatInstant, formatMoney } from '../../shared/format';

@Component({
  selector: 'app-seat-page',
  imports: [RouterLink, EmptyStateComponent, SeatMapComponent],
  templateUrl: './seats.page.html'
})
export class SeatPageComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly seatsApi = inject(SeatsService);
  private readonly holdsApi = inject(HoldsService);
  private readonly checkout = inject(CheckoutSessionService);
  readonly auth = inject(AuthService);

  loading = true;
  holding = false;
  error = '';
  holdNotice = '';
  tripId = '';
  originStopId = '';
  destinationStopId = '';
  originLocationId = '';
  destinationLocationId = '';
  serviceDate = '';
  availability: TripSeatAvailability | null = null;
  decks: SeatDeck[] = [];
  selectedIds: string[] = [];
  holdIdempotencyKey = '';

  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;

  get snapshot() {
    return this.checkout.tripSnapshot(this.tripId);
  }

  get selectedSeats(): TripSeatAvailabilitySeat[] {
    return (this.availability?.seats ?? []).filter((seat) => this.selectedIds.includes(seat.inventoryId));
  }

  get selectedSeatNumbers(): string {
    const numbers = this.selectedSeats.map((seat) => seat.seatNumber).filter((value) => value.length > 0);
    return numbers.join(', ') || 'None';
  }

  ngOnInit(): void {
    this.route.queryParamMap.subscribe(() => {
      this.tripId = this.route.snapshot.paramMap.get('tripId') ?? '';
      this.readQuery();
      this.load();
    });
  }

  toggle(seat: TripSeatAvailabilitySeat): void {
    this.error = '';
    this.selectedIds = toggleSeatSelection(this.selectedIds, seat);
  }

  continue(): void {
    this.error = '';
    if (!this.originStopId || !this.destinationStopId) {
      this.error = 'Origin and destination from search are required.';
      return;
    }
    if (this.selectedIds.length === 0) {
      this.error = 'Select at least one available seat.';
      return;
    }
    if (!this.auth.isAuthenticated()) {
      void this.router.navigate(['/login'], { queryParams: { returnUrl: this.router.url } });
      return;
    }
    const fingerprint = [...this.selectedIds].sort().join(',');
    const existing = this.checkout.read();
    if (!this.holdIdempotencyKey || existing?.seats.map((seat) => seat.inventoryId).sort().join(',') !== fingerprint) {
      this.holdIdempotencyKey = newIdempotencyKey();
    }
    this.holding = true;
    this.holdsApi
      .create(
        this.tripId,
        toCreateHoldRequest({
          originStopId: this.originStopId,
          destinationStopId: this.destinationStopId,
          seatInventoryIds: this.selectedIds,
          idempotencyKey: this.holdIdempotencyKey
        })
      )
      .subscribe({
        next: (hold) => {
          this.holding = false;
          this.checkout.write({
            holdId: hold.holdId,
            tripId: hold.tripId,
            originStopId: hold.originStopId,
            destinationStopId: hold.destinationStopId,
            expiresAt: hold.expiresAt,
            seats: this.selectedSeats.map((seat) => ({
              inventoryId: seat.inventoryId,
              seatNumber: seat.seatNumber,
              seatType: seat.seatType
            })),
            passengers: this.selectedSeats.map((seat) => ({
              seatInventoryId: seat.inventoryId,
              fullName: '',
              age: null,
              gender: null
            })),
            bookingIdempotencyKey: newIdempotencyKey(),
            paymentIdempotencyKey: null,
            bookingId: null,
            tripSnapshot: this.snapshot
          });
          void this.router.navigate(['/checkout', hold.holdId, 'passengers']);
        },
        error: (err) => {
          this.holding = false;
          this.error = readApiError(err);
          this.load();
        }
      });
  }

  private readQuery(): void {
    const query = this.route.snapshot.queryParamMap;
    this.originStopId = query.get('originStopId') ?? '';
    this.destinationStopId = query.get('destinationStopId') ?? '';
    this.originLocationId = query.get('originLocationId') ?? '';
    this.destinationLocationId = query.get('destinationLocationId') ?? '';
    this.serviceDate = query.get('serviceDate') ?? '';
    const holdStatus = query.get('holdStatus');
    if (holdStatus === 'EXPIRED') {
      this.holdNotice = 'Your seat hold expired. Select seats again.';
    } else if (holdStatus === 'CANCELLED') {
      this.holdNotice = 'Your seat hold was cancelled. Select seats again.';
    } else if (holdStatus === 'CONSUMED') {
      this.holdNotice = 'Those seats were already used for a booking.';
    }
  }

  private load(): void {
    this.error = '';
    this.availability = null;
    this.decks = [];
    this.selectedIds = [];
    if (!this.tripId || !this.originStopId || !this.destinationStopId) {
      this.loading = false;
      this.error = 'Open seat selection from search results so origin and destination stops are included.';
      return;
    }
    this.loading = true;
    this.seatsApi.availability(this.tripId, this.originStopId, this.destinationStopId).subscribe({
      next: (availability) => {
        this.availability = availability;
        this.decks = groupSeatsByLayout(availability.seats);
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.error = readApiError(err);
      }
    });
  }
}
