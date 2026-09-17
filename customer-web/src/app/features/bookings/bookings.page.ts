import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { BookingsService } from '../../core/api/bookings.service';
import { Booking } from '../../core/api/models';
import { readApiError } from '../../core/api/api-error';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';
import { formatDate, formatMoney, locationLabel } from '../../shared/format';
import { badgeTone } from './booking-status';

@Component({
  selector: 'app-bookings-page',
  imports: [RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './bookings.page.html'
})
export class BookingsPageComponent implements OnInit {
  private readonly bookingsApi = inject(BookingsService);
  loading = true;
  error = '';
  bookings: Booking[] = [];
  readonly formatDate = formatDate;
  readonly formatMoney = formatMoney;
  readonly locationLabel = locationLabel;
  readonly badgeTone = badgeTone;

  ngOnInit(): void {
    this.bookingsApi.list().subscribe({
      next: (rows) => {
        this.bookings = rows;
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.error = readApiError(err);
      }
    });
  }
}
