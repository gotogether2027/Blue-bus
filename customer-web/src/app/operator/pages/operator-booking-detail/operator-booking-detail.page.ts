import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { formatDate, formatInstant, formatMoney } from '../../../shared/format';
import { operatorStatusTone } from '../../components/operator-status';
import {
  OperatorBooking,
  OperatorBookingPassenger
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-booking-detail-page',
  imports: [RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './operator-booking-detail.page.html'
})
export class OperatorBookingDetailPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);

  loading = true;
  error: OperatorPageError | null = null;
  tripId = '';
  booking: OperatorBooking | null = null;
  readonly formatDate = formatDate;
  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;
  readonly statusTone = operatorStatusTone;

  ngOnInit(): void {
    this.tripId = this.route.snapshot.paramMap.get('tripId') ?? '';
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const bookingId = this.route.snapshot.paramMap.get('bookingId');
    if (!operatorId || !this.tripId || !bookingId) {
      this.loading = false;
      this.error = {
        kind: 'not-found',
        title: 'Booking not found',
        message: 'The requested booking could not be identified.'
      };
      return;
    }

    this.loading = true;
    this.error = null;
    this.api.getTripBooking(operatorId, this.tripId, bookingId).subscribe({
      next: (booking) => {
        this.booking = booking;
        this.loading = false;
      },
      error: (error: unknown) => {
        this.loading = false;
        this.error = this.errors.handle(error);
      }
    });
  }

  passengerFor(passengerId: string | null): OperatorBookingPassenger | null {
    if (!passengerId) {
      return null;
    }
    return (
      this.booking?.passengers.find((passenger) => passenger.passengerId === passengerId) ??
      null
    );
  }
}
