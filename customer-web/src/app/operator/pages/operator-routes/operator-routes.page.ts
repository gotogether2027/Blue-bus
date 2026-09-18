import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { CustomerLocation } from '../../../core/api/models';
import { LocationsService } from '../../../core/api/locations.service';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { OperatorRetryButtonComponent } from '../../components/operator-retry-button';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import {
  operatorLocationLabel,
  orderedRouteStops
} from '../../components/operator-route-references';
import { operatorStatusTone } from '../../components/operator-status';
import {
  OperatorRoute,
  OperatorRouteStatus
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-routes-page',
  imports: [FormsModule, RouterLink, EmptyStateComponent, OperatorRetryButtonComponent, StatusBadgeComponent],
  templateUrl: './operator-routes.page.html'
})
export class OperatorRoutesPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  private readonly locationsApi = inject(LocationsService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private loadVersion = 0;

  loading = true;
  error: OperatorPageError | null = null;
  routes: OperatorRoute[] = [];
  locations: CustomerLocation[] = [];
  searchQuery = '';
  statusFilter: OperatorRouteStatus | 'ALL' = 'ALL';
  readonly statusOptions: OperatorRouteStatus[] = ['ACTIVE', 'INACTIVE'];
  readonly statusTone = operatorStatusTone;
  readonly writeAccessDenied =
    this.route.snapshot.queryParamMap.get('writeAccessDenied') === 'true';

  get selectedOperatorId(): string {
    return this.context.selectedOperatorId() ?? '';
  }

  get filteredRoutes(): OperatorRoute[] {
    const query = this.searchQuery.trim().toLocaleLowerCase();
    return this.routes.filter((route) => {
      if (this.statusFilter !== 'ALL' && route.status !== this.statusFilter) {
        return false;
      }
      if (!query) {
        return true;
      }
      const searchText = [
        route.code,
        route.name,
        this.locationLabel(route.sourceLocationId),
        this.locationLabel(route.destinationLocationId),
        ...orderedRouteStops(route.stops).map((stop) => this.locationLabel(stop.locationId))
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
    this.routes = [];
    this.locations = [];
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
      routes: this.api.listRoutes(operatorId),
      locations: this.locationsApi.list()
    }).subscribe({
      next: ({ routes, locations }) => {
        if (version !== this.loadVersion) {
          return;
        }
        this.routes = routes;
        this.locations = locations;
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

  locationLabel(locationId: string): string {
    return operatorLocationLabel(this.locations, locationId);
  }

  stopSummary(route: OperatorRoute): string {
    const stops = orderedRouteStops(route.stops);
    if (stops.length === 0) {
      return 'No stops yet';
    }
    return stops
      .map((stop) => `${stop.sequenceNumber}. ${this.locationLabel(stop.locationId)}`)
      .join(' → ');
  }
}
