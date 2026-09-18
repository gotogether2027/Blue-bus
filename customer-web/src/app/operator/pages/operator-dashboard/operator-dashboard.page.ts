import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { formatDate, formatInstant, formatMoney } from '../../../shared/format';
import {
  activeBusCount,
  activeRouteCount,
  operatorDashboardToday,
  physicalSeatCounts,
  scopedToOperator,
  tripsOnServiceDate,
  upcomingTrips
} from '../../components/operator-dashboard-summary';
import {
  OperatorOperationalAlert,
  buildOperationalAlerts
} from '../../components/operator-operational-alerts';
import { operatorRoleLabel, operatorStatusTone } from '../../components/operator-status';
import { busSummary, routeSummary } from '../../components/operator-trip-references';
import {
  OperatorBus,
  OperatorProfile,
  OperatorRoute,
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
  private loadVersion = 0;

  loading = true;
  error: OperatorPageError | null = null;
  operator: OperatorProfile | null = null;
  buses: OperatorBus[] = [];
  routes: OperatorRoute[] = [];
  trips: OperatorTrip[] = [];
  todayTrips: OperatorTrip[] = [];
  upcoming: OperatorTrip[] = [];
  upcomingPreview: OperatorTrip[] = [];
  alerts: OperatorOperationalAlert[] = [];
  activeBuses = 0;
  activeRoutes = 0;
  readonly formatDate = formatDate;
  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;
  readonly roleLabel = operatorRoleLabel;
  readonly statusTone = operatorStatusTone;

  alertTone(severity: OperatorOperationalAlert['severity']): 'warn' | 'info' {
    return severity === 'WARNING' ? 'warn' : 'info';
  }

  get selectedOperatorId(): string {
    return this.context.selectedOperatorId() ?? '';
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const version = ++this.loadVersion;
    this.clearDashboard();
    if (!operatorId) {
      this.showAccessDenied();
      return;
    }

    this.loading = true;
    this.error = null;
    forkJoin({
      operator: this.api.getOperator(operatorId),
      buses: this.api.listBuses(operatorId),
      routes: this.api.listRoutes(operatorId),
      trips: this.api.listTrips(operatorId)
    }).subscribe({
      next: ({ operator, buses, routes, trips }) => {
        if (version !== this.loadVersion) {
          return;
        }
        if (operator.id !== operatorId) {
          this.loading = false;
          this.error = {
            kind: 'not-found',
            title: 'Resource not found',
            message: 'The requested operator resource was not found.'
          };
          return;
        }
        this.operator = operator;
        this.buses = scopedToOperator(buses, operatorId);
        this.routes = scopedToOperator(routes, operatorId);
        this.trips = scopedToOperator(trips, operatorId);
        this.activeBuses = activeBusCount(this.buses, operatorId);
        this.activeRoutes = activeRouteCount(this.routes, operatorId);
        this.todayTrips = tripsOnServiceDate(
          this.trips,
          operatorId,
          operatorDashboardToday()
        );
        this.upcoming = upcomingTrips(this.trips, operatorId);
        this.upcomingPreview = this.upcoming.slice(0, 5);
        this.alerts = buildOperationalAlerts(
          operatorId,
          this.buses,
          this.routes,
          this.trips
        );
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

  physicalSeats(trip: OperatorTrip): {
    available: number;
    blocked: number;
    total: number;
  } {
    return physicalSeatCounts(trip.seatInventory);
  }

  private clearDashboard(): void {
    this.operator = null;
    this.buses = [];
    this.routes = [];
    this.trips = [];
    this.todayTrips = [];
    this.upcoming = [];
    this.upcomingPreview = [];
    this.alerts = [];
    this.activeBuses = 0;
    this.activeRoutes = 0;
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
