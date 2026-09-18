import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin, of, switchMap } from 'rxjs';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { formatDate, formatInstant, formatMoney } from '../../../shared/format';
import { operatorStatusTone } from '../../components/operator-status';
import { OperatorBus, OperatorTrip } from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-trip-detail-page',
  imports: [RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './operator-trip-detail.page.html'
})
export class OperatorTripDetailPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);

  loading = true;
  error: OperatorPageError | null = null;
  trip: OperatorTrip | null = null;
  bus: OperatorBus | null = null;
  readonly formatDate = formatDate;
  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;
  readonly statusTone = operatorStatusTone;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const tripId = this.route.snapshot.paramMap.get('tripId');
    if (!operatorId || !tripId) {
      this.loading = false;
      this.error = {
        kind: 'not-found',
        title: 'Trip not found',
        message: 'The requested trip could not be identified.'
      };
      return;
    }

    this.loading = true;
    this.error = null;
    this.api
      .getTrip(operatorId, tripId)
      .pipe(
        switchMap((trip) =>
          forkJoin({
            trip: of(trip),
            bus: this.api.getBus(operatorId, trip.busId)
          })
        )
      )
      .subscribe({
        next: ({ trip, bus }) => {
          this.trip = trip;
          this.bus = bus;
          this.loading = false;
        },
        error: (error: unknown) => {
          this.loading = false;
          this.error = this.errors.handle(error);
        }
      });
  }
}
