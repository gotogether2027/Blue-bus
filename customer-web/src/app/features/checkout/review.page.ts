import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval } from 'rxjs';
import { BookingsService } from '../../core/api/bookings.service';
import { HoldsService } from '../../core/api/holds.service';
import { readApiError } from '../../core/api/api-error';
import { newIdempotencyKey } from '../../core/api/idempotency';
import { toCreateBookingRequest } from '../../core/api/seats';
import { CheckoutSession, CheckoutSessionService } from '../../core/checkout/checkout-session.service';
import { formatInstant, formatMoney } from '../../shared/format';
import { remainingText } from './passengers.page';

@Component({
  selector: 'app-review-page',
  templateUrl: './review.page.html'
})
export class ReviewPageComponent implements OnInit {
  private readonly checkoutApi = inject(CheckoutSessionService);
  private readonly holdsApi = inject(HoldsService);
  private readonly bookingsApi = inject(BookingsService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  session: CheckoutSession | null = null;
  remainingLabel = '';
  error = '';
  submitting = false;

  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;

  ngOnInit(): void {
    this.session = this.checkoutApi.read();
    this.refreshHold();
    interval(15000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refreshHold());
  }

  confirm(): void {
    if (!this.session) {
      return;
    }
    this.error = '';
    this.submitting = true;
    const idempotencyKey = this.session.bookingIdempotencyKey || newIdempotencyKey();
    this.checkoutApi.patch({ bookingIdempotencyKey: idempotencyKey });
    this.bookingsApi
      .create(
        toCreateBookingRequest({
          holdId: this.session.holdId,
          originStopId: this.session.originStopId,
          destinationStopId: this.session.destinationStopId,
          idempotencyKey,
          passengers: this.session.passengers
        })
      )
      .subscribe({
        next: (booking) => {
          this.submitting = false;
          this.checkoutApi.patch({ bookingId: booking.bookingId, paymentIdempotencyKey: newIdempotencyKey() });
          void this.router.navigate(['/payment', booking.bookingId]);
        },
        error: (err) => {
          this.submitting = false;
          this.error = readApiError(err);
        }
      });
  }

  back(): void {
    if (this.session) {
      void this.router.navigate(['/checkout', this.session.holdId, 'passengers']);
    }
  }

  private refreshHold(): void {
    if (!this.session) {
      return;
    }
    this.holdsApi.get(this.session.holdId).subscribe({
      next: (hold) => {
        this.remainingLabel = remainingText(hold.expiresAt);
        if (hold.status !== 'ACTIVE') {
          const session = this.session;
          this.checkoutApi.clear();
          void this.router.navigate(['/trips', session?.tripId, 'seats'], {
            queryParams: {
              originStopId: session?.originStopId,
              destinationStopId: session?.destinationStopId,
              holdStatus: hold.status
            }
          });
        }
      },
      error: (err) => (this.error = readApiError(err))
    });
  }
}
