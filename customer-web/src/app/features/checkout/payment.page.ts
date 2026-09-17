import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { BookingsService } from '../../core/api/bookings.service';
import { PaymentsService } from '../../core/api/payments.service';
import { Booking, PaymentAttempt, PaymentInitiation, PaymentStatus } from '../../core/api/models';
import { readApiError } from '../../core/api/api-error';
import { newIdempotencyKey } from '../../core/api/idempotency';
import { CheckoutSessionService } from '../../core/checkout/checkout-session.service';
import { openRazorpayCheckout } from '../../core/payments/razorpay-checkout';
import { formatMoney } from '../../shared/format';
import { StatusBadgeComponent } from '../../shared/status-badge.component';
import { badgeTone } from '../bookings/booking-status';

@Component({
  selector: 'app-payment-page',
  imports: [StatusBadgeComponent],
  templateUrl: './payment.page.html'
})
export class PaymentPageComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly bookingsApi = inject(BookingsService);
  private readonly paymentsApi = inject(PaymentsService);
  private readonly checkout = inject(CheckoutSessionService);

  booking: Booking | null = null;
  initiation: PaymentInitiation | null = null;
  payment: PaymentAttempt | null = null;
  loading = true;
  paying = false;
  error = '';
  bookingId = '';
  private paymentKey = '';
  private alive = true;
  private timers: Array<ReturnType<typeof setTimeout>> = [];

  readonly formatMoney = formatMoney;
  readonly badgeTone = badgeTone;

  ngOnDestroy(): void {
    this.alive = false;
    this.timers.forEach((timer) => clearTimeout(timer));
  }

  ngOnInit(): void {
    this.bookingId = this.route.snapshot.paramMap.get('bookingId') ?? '';
    if (!this.bookingId) {
      this.loading = false;
      this.error = 'Booking was not found.';
      return;
    }
    this.bookingsApi.get(this.bookingId).subscribe({
      next: (booking) => {
        this.booking = booking;
        if (booking.status === 'CONFIRMED' || booking.paymentStatus === 'SUCCEEDED') {
          this.loading = false;
          void this.router.navigate(['/bookings', booking.bookingId, 'confirmation']);
          return;
        }
        this.startPayment(booking.bookingId);
      },
      error: (err) => {
        this.loading = false;
        this.error = readApiError(err);
      }
    });
  }

  pay(): void {
    if (!this.initiation?.providerOrderId || !this.initiation.checkoutReference) {
      this.error = 'Payment checkout is not ready yet. Wait a moment and try again.';
      this.pollInitiation(0);
      return;
    }
    this.paying = true;
    this.error = '';
    void openRazorpayCheckout({
      key: this.initiation.checkoutReference,
      orderId: this.initiation.providerOrderId,
      currency: this.initiation.currency
    })
      .then((response) => {
        this.paymentsApi
          .verifyCheckout(this.initiation!.paymentAttemptId, {
            razorpayPaymentId: response.razorpay_payment_id,
            razorpayOrderId: response.razorpay_order_id,
            razorpaySignature: response.razorpay_signature
          })
          .subscribe({
            next: (payment) => {
              this.payment = payment;
              this.paying = false;
              this.afterPaymentStatus(payment.status);
            },
            error: (err) => {
              this.paying = false;
              this.error = readApiError(err);
              this.pollStatus(this.initiation!.paymentAttemptId, 0);
            }
          });
      })
      .catch((err: unknown) => {
        this.paying = false;
        this.error = err instanceof Error ? err.message : 'Checkout was not completed.';
      });
  }

  private startPayment(bookingId: string): void {
    this.paymentKey = this.stablePaymentKey(bookingId);
    this.paymentsApi.initiate(bookingId, this.paymentKey).subscribe({
      next: (initiation) => {
        this.initiation = initiation;
        this.loading = false;
        if (initiation.status === 'SUCCEEDED') {
          void this.router.navigate(['/bookings', bookingId, 'confirmation']);
          return;
        }
        if (!initiation.providerOrderId || !initiation.checkoutReference || initiation.status === 'INITIATING') {
          this.pollInitiation(0);
        }
      },
      error: (err) => {
        this.loading = false;
        this.error = readApiError(err);
      }
    });
  }

  private pollInitiation(attempt: number): void {
    if (!this.alive || attempt >= 8 || !this.paymentKey) {
      return;
    }
    this.timers.push(setTimeout(() => {
      if (!this.alive) {
        return;
      }
      this.paymentsApi.initiate(this.bookingId, this.paymentKey).subscribe({
        next: (initiation) => {
          this.initiation = initiation;
          if (initiation.status === 'SUCCEEDED') {
            void this.router.navigate(['/bookings', this.bookingId, 'confirmation']);
            return;
          }
          if (!initiation.providerOrderId || !initiation.checkoutReference || initiation.status === 'INITIATING') {
            this.pollInitiation(attempt + 1);
          }
        },
        error: () => undefined
      });
    }, 2500));
  }

  private pollStatus(paymentAttemptId: string, attempt: number): void {
    if (!this.alive || attempt >= 8) {
      return;
    }
    this.timers.push(setTimeout(() => {
      if (!this.alive) {
        return;
      }
      this.paymentsApi.get(paymentAttemptId).subscribe({
        next: (payment) => {
          this.payment = payment;
          this.afterPaymentStatus(payment.status, paymentAttemptId, attempt);
        },
        error: () => undefined
      });
    }, 2500));
  }

  private afterPaymentStatus(status: PaymentStatus, paymentAttemptId?: string, attempt = 0): void {
    if (!this.alive) {
      return;
    }
    if (status === 'SUCCEEDED') {
      void this.router.navigate(['/bookings', this.bookingId, 'confirmation']);
      return;
    }
    if (status === 'FAILED' || status === 'CANCELLED' || status === 'EXPIRED') {
      this.error = 'Payment was not completed. You can try again.';
      return;
    }
    if ((status === 'PENDING' || status === 'INITIATING') && paymentAttemptId) {
      this.pollStatus(paymentAttemptId, attempt + 1);
    }
  }

  private stablePaymentKey(bookingId: string): string {
    const storageKey = `blue-bus.pay-key.${bookingId}`;
    const existing = sessionStorage.getItem(storageKey);
    if (existing) {
      this.checkout.patch({ bookingId, paymentIdempotencyKey: existing });
      return existing;
    }
    const created = this.checkout.paymentKeyFor(bookingId) || newIdempotencyKey();
    sessionStorage.setItem(storageKey, created);
    this.checkout.patch({ bookingId, paymentIdempotencyKey: created });
    return created;
  }
}
