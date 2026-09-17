import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { BookingsService } from '../../core/api/bookings.service';
import { TicketsService } from '../../core/api/tickets.service';
import { Booking, Ticket } from '../../core/api/models';
import { readApiError } from '../../core/api/api-error';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';
import { formatInstant, formatMoney, locationLabel } from '../../shared/format';
import { badgeTone } from '../bookings/booking-status';

@Component({
  selector: 'app-confirmation-page',
  imports: [RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './confirmation.page.html'
})
export class ConfirmationPageComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly bookingsApi = inject(BookingsService);
  private readonly ticketsApi = inject(TicketsService);

  loading = true;
  error = '';
  booking: Booking | null = null;
  ticket: Ticket | null = null;
  ticketPending = false;
  ticketPolls = 0;
  private ticketTimer: ReturnType<typeof setTimeout> | null = null;

  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;
  readonly locationLabel = locationLabel;
  readonly badgeTone = badgeTone;

  ngOnInit(): void {
    const bookingId = this.route.snapshot.paramMap.get('bookingId');
    if (!bookingId) {
      this.loading = false;
      this.error = 'Booking was not found.';
      return;
    }
    this.loadBooking(bookingId);
  }

  ngOnDestroy(): void {
    if (this.ticketTimer !== null) {
      clearTimeout(this.ticketTimer);
    }
  }

  private loadBooking(bookingId: string): void {
    this.bookingsApi.get(bookingId).subscribe({
      next: (booking) => {
        this.booking = booking;
        this.loading = false;
        this.loadTicket(booking);
      },
      error: (err) => {
        this.loading = false;
        this.error = readApiError(err);
      }
    });
  }

  private loadTicket(booking: Booking): void {
    if (!booking.ticketId && !booking.ticketStatus && booking.status !== 'CONFIRMED') {
      this.ticketPending = false;
      return;
    }
    this.ticketsApi.getByBooking(booking.bookingId).subscribe({
      next: (ticket) => {
        this.ticket = ticket;
        this.ticketPending = false;
      },
      error: (err) => {
        if (err instanceof HttpErrorResponse && err.status === 404) {
          this.ticketPending = true;
          this.scheduleTicketRefresh(booking.bookingId);
          return;
        }
        this.ticketPending = true;
      }
    });
  }

  private scheduleTicketRefresh(bookingId: string): void {
    if (this.ticketPolls >= 5) {
      return;
    }
    this.ticketPolls += 1;
    this.ticketTimer = setTimeout(() => {
      this.bookingsApi.get(bookingId).subscribe({
        next: (booking) => {
          this.booking = booking;
          this.loadTicket(booking);
        },
        error: () => undefined
      });
    }, 4000);
  }
}
