import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { formatDate, formatMoney } from '../../../shared/format';
import { operatorStatusTone } from '../../components/operator-status';
import { OperatorBooking } from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-bookings-page',
  imports: [RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './operator-bookings.page.html'
})
export class OperatorBookingsPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);

  loading = true;
  error: OperatorPageError | null = null;
  tripId = '';
  bookings: OperatorBooking[] = [];
  readonly formatDate = formatDate;
  readonly formatMoney = formatMoney;
  readonly statusTone = operatorStatusTone;

  ngOnInit(): void {
    this.tripId = this.route.snapshot.paramMap.get('tripId') ?? '';
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    if (!operatorId || !this.tripId) {
      this.loading = false;
      this.error = {
        kind: 'not-found',
        title: 'Trip bookings not found',
        message: 'The requested trip could not be identified.'
      };
      return;
    }

    this.loading = true;
    this.error = null;
    this.api.listTripBookings(operatorId, this.tripId).subscribe({
      next: (bookings) => {
        this.bookings = bookings;
        this.loading = false;
      },
      error: (error: unknown) => {
        this.loading = false;
        this.error = this.errors.handle(error);
      }
    });
  }
}
