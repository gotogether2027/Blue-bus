import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { TripStatus } from '../../../core/api/models';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { OperatorRetryButtonComponent } from '../../components/operator-retry-button';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { formatDate, formatInstant, formatMoney } from '../../../shared/format';
import { operatorStatusTone } from '../../components/operator-status';
import {
  TRIP_STATUSES,
  busSummary,
  routeSummary
} from '../../components/operator-trip-references';
import { OperatorBus, OperatorRoute, OperatorTrip } from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-trips-page',
  imports: [FormsModule, RouterLink, EmptyStateComponent, OperatorRetryButtonComponent, StatusBadgeComponent],
  templateUrl: './operator-trips.page.html'
})
export class OperatorTripsPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private loadVersion = 0;

  loading = true;
  error: OperatorPageError | null = null;
  trips: OperatorTrip[] = [];
  buses = new Map<string, OperatorBus>();
  routes = new Map<string, OperatorRoute>();
  searchQuery = '';
  serviceDateFilter = '';
  statusFilter: TripStatus | 'ALL' = 'ALL';
  readonly statusOptions = TRIP_STATUSES;
  readonly formatDate = formatDate;
  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;
  readonly statusTone = operatorStatusTone;
  readonly writeAccessDenied =
    this.route.snapshot.queryParamMap.get('writeAccessDenied') === 'true';

  get selectedOperatorId(): string {
    return this.context.selectedOperatorId() ?? '';
  }

  get filteredTrips(): OperatorTrip[] {
    const query = this.searchQuery.trim().toLocaleLowerCase();
    return this.trips.filter((trip) => {
      if (!query) {
        return true;
      }
      const searchText = [
        trip.id,
        trip.status,
        trip.serviceDate,
        trip.busId,
        trip.routeId,
        this.busLabel(trip.busId),
        this.routeLabel(trip.routeId)
      ]
        .join(' ')
        .toLocaleLowerCase();
      return searchText.includes(query);
    });
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const version = ++this.loadVersion;
    this.trips = [];
    this.buses = new Map();
    this.routes = new Map();
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
    const filters: { serviceDate?: string; status?: TripStatus } = {};
    const serviceDate = this.serviceDateFilter.trim();
    if (serviceDate) {
      filters.serviceDate = serviceDate;
    }
    if (this.statusFilter !== 'ALL') {
      filters.status = this.statusFilter;
    }

    forkJoin({
      trips: this.api.listTrips(operatorId, filters),
      buses: this.api.listBuses(operatorId),
      routes: this.api.listRoutes(operatorId)
    }).subscribe({
      next: ({ trips, buses, routes }) => {
        if (version !== this.loadVersion) {
          return;
        }
        this.trips = trips;
        this.buses = new Map(buses.map((bus) => [bus.id, bus]));
        this.routes = new Map(routes.map((route) => [route.id, route]));
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

  busLabel(busId: string): string {
    return busSummary(this.buses.get(busId), busId);
  }

  routeLabel(routeId: string): string {
    return routeSummary(this.routes.get(routeId), routeId);
  }
}
