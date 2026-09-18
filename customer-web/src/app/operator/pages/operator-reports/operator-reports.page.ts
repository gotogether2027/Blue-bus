import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { formatDate, formatInstant, formatMoney } from '../../../shared/format';
import {
  activeBusCount,
  activeRouteCount,
  physicalSeatCounts,
  scopedToOperator,
  upcomingTrips
} from '../../components/operator-dashboard-summary';
import {
  physicalInventoryOnTrips,
  sortedReportTrips,
  tripCountByStatus
} from '../../components/operator-report-summary';
import { operatorRoleLabel, operatorStatusTone } from '../../components/operator-status';
import { busSummary, routeSummary } from '../../components/operator-trip-references';
import { OperatorBus, OperatorRoute, OperatorTrip } from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-reports-page',
  imports: [FormsModule, RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './operator-reports.page.html'
})
export class OperatorReportsPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private loadVersion = 0;
  private loadedOperatorId: string | null = null;

  loading = true;
  error: OperatorPageError | null = null;
  serviceDateFilter = '';
  buses: OperatorBus[] = [];
  routes: OperatorRoute[] = [];
  trips: OperatorTrip[] = [];
  activeBuses = 0;
  activeRoutes = 0;
  scheduledTrips = 0;
  completedTrips = 0;
  cancelledTrips = 0;
  upcomingTripCount = 0;
  listedPhysicalAvailable = 0;
  listedPhysicalBlocked = 0;
  readonly formatDate = formatDate;
  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;
  readonly roleLabel = operatorRoleLabel;
  readonly statusTone = operatorStatusTone;
  readonly physicalSeats = physicalSeatCounts;

  get selectedOperatorId(): string {
    return this.context.selectedOperatorId() ?? '';
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const version = ++this.loadVersion;
    if (this.loadedOperatorId !== operatorId) {
      this.serviceDateFilter = '';
      this.loadedOperatorId = operatorId;
    }
    this.clearReport();
    if (!operatorId) {
      this.showAccessDenied();
      return;
    }

    this.loading = true;
    this.error = null;
    const serviceDate = this.serviceDateFilter.trim();
    forkJoin({
      buses: this.api.listBuses(operatorId),
      routes: this.api.listRoutes(operatorId),
      trips: this.api.listTrips(operatorId, serviceDate ? { serviceDate } : {})
    }).subscribe({
      next: ({ buses, routes, trips }) => {
        if (version !== this.loadVersion) {
          return;
        }
        this.buses = scopedToOperator(buses, operatorId);
        this.routes = scopedToOperator(routes, operatorId);
        this.trips = sortedReportTrips(trips, operatorId);
        this.activeBuses = activeBusCount(this.buses, operatorId);
        this.activeRoutes = activeRouteCount(this.routes, operatorId);
        this.scheduledTrips = tripCountByStatus(this.trips, operatorId, 'SCHEDULED');
        this.completedTrips = tripCountByStatus(this.trips, operatorId, 'COMPLETED');
        this.cancelledTrips = tripCountByStatus(this.trips, operatorId, 'CANCELLED');
        this.upcomingTripCount = upcomingTrips(this.trips, operatorId).length;
        const inventory = physicalInventoryOnTrips(this.trips, operatorId);
        this.listedPhysicalAvailable = inventory.available;
        this.listedPhysicalBlocked = inventory.blocked;
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
    return busSummary(
      this.buses.find((bus) => bus.id === busId),
      busId
    );
  }

  routeLabel(routeId: string): string {
    return routeSummary(
      this.routes.find((route) => route.id === routeId),
      routeId
    );
  }

  private clearReport(): void {
    this.buses = [];
    this.routes = [];
    this.trips = [];
    this.activeBuses = 0;
    this.activeRoutes = 0;
    this.scheduledTrips = 0;
    this.completedTrips = 0;
    this.cancelledTrips = 0;
    this.upcomingTripCount = 0;
    this.listedPhysicalAvailable = 0;
    this.listedPhysicalBlocked = 0;
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
