import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval } from 'rxjs';
import { HoldsService } from '../../core/api/holds.service';
import { readApiError } from '../../core/api/api-error';
import { validatePassengers } from '../../core/api/seats';
import { CheckoutPassenger, CheckoutSession, CheckoutSessionService } from '../../core/checkout/checkout-session.service';

@Component({
  selector: 'app-passengers-page',
  imports: [FormsModule],
  templateUrl: './passengers.page.html'
})
export class PassengersPageComponent implements OnInit {
  private readonly checkoutApi = inject(CheckoutSessionService);
  private readonly holdsApi = inject(HoldsService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  session: CheckoutSession | null = null;
  passengers: CheckoutPassenger[] = [];
  error = '';
  remainingLabel = '';

  ngOnInit(): void {
    this.session = this.checkoutApi.read();
    this.passengers = (this.session?.passengers ?? []).map((passenger) => ({ ...passenger }));
    this.refreshHold();
    interval(15000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refreshHold());
  }

  continue(): void {
    if (!this.session) {
      return;
    }
    this.error = '';
    const message = validatePassengers(
      this.session.seats.map((seat) => seat.inventoryId),
      this.passengers
    );
    if (message) {
      this.error = message;
      return;
    }
    this.checkoutApi.patch({ passengers: this.passengers.map((passenger) => ({ ...passenger })) });
    void this.router.navigate(['/checkout', this.session.holdId, 'review']);
  }

  cancelHold(): void {
    if (!this.session) {
      return;
    }
    this.holdsApi.cancel(this.session.holdId).subscribe({
      next: () => this.leaveSeats('CANCELLED'),
      error: (err) => (this.error = readApiError(err))
    });
  }

  seatNumber(seatInventoryId: string): string {
    return this.session?.seats.find((seat) => seat.inventoryId === seatInventoryId)?.seatNumber || seatInventoryId;
  }

  private refreshHold(): void {
    if (!this.session) {
      return;
    }
    this.holdsApi.get(this.session.holdId).subscribe({
      next: (hold) => {
        this.remainingLabel = remainingText(hold.expiresAt);
        if (hold.status !== 'ACTIVE') {
          this.leaveSeats(hold.status);
        }
      },
      error: (err) => (this.error = readApiError(err))
    });
  }

  private leaveSeats(holdStatus: string): void {
    const session = this.session;
    this.checkoutApi.clear();
    if (!session) {
      void this.router.navigate(['/']);
      return;
    }
    void this.router.navigate(['/trips', session.tripId, 'seats'], {
      queryParams: {
        originStopId: session.originStopId,
        destinationStopId: session.destinationStopId,
        holdStatus
      }
    });
  }
}

export function remainingText(expiresAt: string): string {
  const ms = new Date(expiresAt).getTime() - Date.now();
  if (Number.isNaN(ms) || ms <= 0) {
    return 'Hold time remaining is confirmed by the server.';
  }
  const minutes = Math.ceil(ms / 60000);
  return minutes <= 1 ? 'Less than 1 minute left on this hold (server time is authoritative).' : `About ${minutes} minutes left on this hold (server time is authoritative).`;
}
