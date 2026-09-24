import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { BookingsService } from '../../core/api/bookings.service';
import { PaymentsService } from '../../core/api/payments.service';
import { RefundsService } from '../../core/api/refunds.service';
import { TicketsService } from '../../core/api/tickets.service';
import { Booking, PaymentAttempt, Refund } from '../../core/api/models';
import { readApiError } from '../../core/api/api-error';
import { readPdfDownloadError, savePdfBlob, ticketPdfFilename } from '../../core/api/ticket-pdf';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';
import { formatInstant, formatMoney, locationLabel } from '../../shared/format';
import { badgeTone, canCancel, hasTicket } from './booking-status';

@Component({
  selector: 'app-booking-detail-page',
  imports: [RouterLink, FormsModule, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './booking-detail.page.html'
})
export class BookingDetailPageComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly bookingsApi = inject(BookingsService);
  private readonly paymentsApi = inject(PaymentsService);
  private readonly refundsApi = inject(RefundsService);
  private readonly ticketsApi = inject(TicketsService);

  loading = true;
  error = '';
  actionError = '';
  booking: Booking | null = null;
  payments: PaymentAttempt[] | null = null;
  refunds: Refund[] | null = null;
  cancelReason = '';
  cancelling = false;
  downloadingPdf = false;

  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;
  readonly locationLabel = locationLabel;
  readonly badgeTone = badgeTone;
  readonly canCancel = canCancel;
  readonly hasTicket = hasTicket;

  ngOnInit(): void {
    const bookingId = this.route.snapshot.paramMap.get('bookingId');
    if (!bookingId) {
      this.loading = false;
      this.error = 'Booking was not found.';
      return;
    }
    this.bookingsApi.get(bookingId).subscribe({
      next: (booking) => {
        this.booking = booking;
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.error = readApiError(err);
      }
    });
  }

  downloadPdf(): void {
    if (!this.booking || this.downloadingPdf) {
      return;
    }
    this.downloadingPdf = true;
    this.actionError = '';
    this.ticketsApi.downloadPdf(this.booking.bookingId).subscribe({
      next: (blob) => {
        this.downloadingPdf = false;
        const booking = this.booking;
        if (!booking) {
          return;
        }
        savePdfBlob(blob, ticketPdfFilename(booking.ticketNumber, booking.bookingId));
      },
      error: (err) => {
        this.downloadingPdf = false;
        this.actionError = readPdfDownloadError(err);
      }
    });
  }

  loadPayments(): void {
    if (!this.booking) {
      return;
    }
    this.actionError = '';
    this.paymentsApi.listByBooking(this.booking.bookingId).subscribe({
      next: (rows) => (this.payments = rows),
      error: (err) => (this.actionError = readApiError(err))
    });
  }

  loadRefunds(): void {
    if (!this.booking) {
      return;
    }
    this.actionError = '';
    this.refundsApi.listByBooking(this.booking.bookingId).subscribe({
      next: (rows) => (this.refunds = rows),
      error: (err) => (this.actionError = readApiError(err))
    });
  }

  cancel(): void {
    if (!this.booking || !canCancel(this.booking.status)) {
      return;
    }
    this.cancelling = true;
    this.actionError = '';
    this.bookingsApi
      .cancel(this.booking.bookingId, this.cancelReason.trim() ? { reason: this.cancelReason.trim() } : {})
      .subscribe({
        next: (response) => {
          this.booking = response.booking;
          this.cancelling = false;
        },
        error: (err) => {
          this.cancelling = false;
          this.actionError = readApiError(err);
        }
      });
  }
}
