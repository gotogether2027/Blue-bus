import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { formatDate, formatInstant } from '../../../shared/format';
import { operatorRoleLabel, operatorStatusTone } from '../../components/operator-status';
import {
  OperatorBus,
  OperatorProfile,
  OperatorTrip
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-dashboard-page',
  imports: [RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './operator-dashboard.page.html'
})
export class OperatorDashboardPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);

  loading = true;
  error: OperatorPageError | null = null;
  operator: OperatorProfile | null = null;
  buses: OperatorBus[] = [];
  trips: OperatorTrip[] = [];
  tripPreview: OperatorTrip[] = [];
  tripPreviewTitle = 'Upcoming trips';
  readonly formatDate = formatDate;
  readonly formatInstant = formatInstant;
  readonly roleLabel = operatorRoleLabel;
  readonly statusTone = operatorStatusTone;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    if (!operatorId) {
      this.loading = false;
      this.error = {
        kind: 'forbidden',
        title: 'Operator access denied',
        message: "You don't have access to this operator."
      };
      return;
    }

    this.loading = true;
    this.error = null;
    forkJoin({
      operator: this.api.getOperator(operatorId),
      buses: this.api.listBuses(operatorId),
      trips: this.api.listTrips(operatorId)
    }).subscribe({
      next: ({ operator, buses, trips }) => {
        this.operator = operator;
        this.buses = buses;
        this.trips = trips;
        this.setTripPreview(trips);
        this.loading = false;
      },
      error: (error: unknown) => {
        this.loading = false;
        this.error = this.errors.handle(error);
      }
    });
  }

  private setTripPreview(trips: OperatorTrip[]): void {
    const now = Date.now();
    const upcoming = trips.filter((trip) => {
      const departure = new Date(trip.scheduledDepartureAt).getTime();
      return !Number.isNaN(departure) && departure >= now;
    });
    if (upcoming.length > 0) {
      this.tripPreviewTitle = 'Upcoming trips';
      this.tripPreview = upcoming.slice(0, 5);
      return;
    }
    this.tripPreviewTitle = 'Recent trips';
    this.tripPreview = trips.slice(-5).reverse();
  }
}
