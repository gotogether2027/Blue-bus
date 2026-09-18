import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
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
  selector: 'app-operator-trips-page',
  imports: [RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './operator-trips.page.html'
})
export class OperatorTripsPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);

  loading = true;
  error: OperatorPageError | null = null;
  trips: OperatorTrip[] = [];
  buses = new Map<string, OperatorBus>();
  readonly formatDate = formatDate;
  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;
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
      trips: this.api.listTrips(operatorId),
      buses: this.api.listBuses(operatorId)
    }).subscribe({
      next: ({ trips, buses }) => {
        this.trips = trips;
        this.buses = new Map(buses.map((bus) => [bus.id, bus]));
        this.loading = false;
      },
      error: (error: unknown) => {
        this.loading = false;
        this.error = this.errors.handle(error);
      }
    });
  }

  busLabel(busId: string): string {
    const bus = this.buses.get(busId);
    return bus?.displayName || bus?.registrationNumber || busId;
  }
}
