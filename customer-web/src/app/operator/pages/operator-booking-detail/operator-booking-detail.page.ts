import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { formatDate, formatInstant, formatMoney } from '../../../shared/format';
import {
  bookingBelongsToOperatorTrip,
  bookingConfirmationLabel,
  bookingPassenger,
  bookingStopLabel
} from '../../components/operator-booking-references';
import { operatorStatusTone } from '../../components/operator-status';
import { OperatorTripOpsNavComponent } from '../../components/operator-trip-ops-nav';
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
  imports: [RouterLink, EmptyStateComponent, StatusBadgeComponent, OperatorTripOpsNavComponent],
  templateUrl: './operator-booking-detail.page.html'
})
export class OperatorBookingDetailPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private loadVersion = 0;

  loading = true;
  error: OperatorPageError | null = null;
  tripId = '';
  booking: OperatorBooking | null = null;
  readonly formatDate = formatDate;
  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;
  readonly statusTone = operatorStatusTone;
  readonly confirmationLabel = bookingConfirmationLabel;

  ngOnInit(): void {
    this.tripId = this.route.snapshot.paramMap.get('tripId') ?? '';
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const tripId = this.route.snapshot.paramMap.get('tripId') ?? '';
    const bookingId = this.route.snapshot.paramMap.get('bookingId');
    const version = ++this.loadVersion;
    this.tripId = tripId;
    this.booking = null;
    if (!operatorId) {
      this.showAccessDenied();
      return;
    }
    if (!tripId || !bookingId) {
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
    this.api.getTripBooking(operatorId, tripId, bookingId).subscribe({
      next: (booking) => {
        if (version !== this.loadVersion) {
          return;
        }
        if (!bookingBelongsToOperatorTrip(booking, operatorId, tripId)) {
          this.loading = false;
          this.error = {
            kind: 'not-found',
            title: 'Booking not found',
            message: 'This booking does not belong to the selected operator trip.'
          };
          return;
        }
        this.booking = booking;
        this.loading = false;
      },
      error: (error: unknown) => {
        if (version !== this.loadVersion) {
          return;
        }
        this.loading = false;
        this.error = this.errors.handle(error);
      }
    });
  }

  passengerFor(passengerId: string | null): OperatorBookingPassenger | null {
    if (!this.booking) {
      return null;
    }
    return bookingPassenger(this.booking, passengerId);
  }

  stopLabel(sequence: number): string {
    if (!this.booking) {
      return `Seq ${sequence}`;
    }
    return bookingStopLabel(this.booking, sequence);
  }

  private showAccessDenied(): void {
    this.loading = false;
    this.error = {
      kind: 'forbidden',
      title: 'Operator access denied',
      message: "You don't have access to this operator."
    };
  }
}
