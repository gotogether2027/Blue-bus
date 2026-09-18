import { Component, HostListener, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable, forkJoin } from 'rxjs';
import { CustomerLocation } from '../../../core/api/models';
import { LocationsService } from '../../../core/api/locations.service';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import {
  OperatorRouteActionError,
  readOperatorRouteActionError
} from '../../components/operator-route-errors';
import {
  STRUCTURAL_ROUTE_RESTRICTION,
  operatorLocationLabel,
  orderedRouteStops,
  routeHasTrips
} from '../../components/operator-route-references';
import { operatorStatusTone } from '../../components/operator-status';
import {
  OperatorRoute,
  OperatorRoutePoint,
  OperatorRouteStop
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-route-detail-page',
  imports: [RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './operator-route-detail.page.html'
})
export class OperatorRouteDetailPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  private readonly locationsApi = inject(LocationsService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private loadVersion = 0;

  loading = true;
  error: OperatorPageError | null = null;
  routeDetail: OperatorRoute | null = null;
  locations: CustomerLocation[] = [];
  hasTrips = false;
  actionError: OperatorRouteActionError | null = null;
  successMessage: string | null = null;
  pendingAction: RouteLifecycleAction | null = null;
  pendingPoint: OperatorRoutePoint | null = null;
  lifecycleSubmitting = false;
  readonly statusTone = operatorStatusTone;
  readonly structuralRestriction = STRUCTURAL_ROUTE_RESTRICTION;

  ngOnInit(): void {
    if (this.route.snapshot.queryParamMap.get('created') === 'true') {
      this.successMessage = 'Route created successfully.';
    } else if (this.route.snapshot.queryParamMap.get('updated') === 'true') {
      this.successMessage = 'Route details updated successfully.';
    }
    this.load();
  }

  get orderedStops(): OperatorRouteStop[] {
    return orderedRouteStops(this.routeDetail?.stops ?? []);
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const routeId = this.route.snapshot.paramMap.get('routeId');
    const version = ++this.loadVersion;
    this.routeDetail = null;
    this.locations = [];
    this.hasTrips = false;
    this.actionError = null;
    this.pendingAction = null;
    this.pendingPoint = null;
    if (!operatorId || !routeId) {
      this.loading = false;
      this.error = {
        kind: 'not-found',
        title: 'Route not found',
        message: 'The requested route could not be identified.'
      };
      return;
    }

    this.loading = true;
    this.error = null;
    forkJoin({
      route: this.api.getRoute(operatorId, routeId),
      trips: this.api.listTrips(operatorId),
      locations: this.locationsApi.list()
    }).subscribe({
      next: ({ route, trips, locations }) => {
        if (version !== this.loadVersion) {
          return;
        }
        this.routeDetail = route;
        this.locations = locations;
        this.hasTrips = routeHasTrips(trips, route.id);
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

  requestRouteLifecycle(action: 'activate' | 'deactivate'): void {
    if (!this.context.canManageOperator() || this.lifecycleSubmitting) {
      return;
    }
    this.actionError = null;
    this.pendingPoint = null;
    this.pendingAction = action;
  }

  requestPointLifecycle(point: OperatorRoutePoint, action: 'activatePoint' | 'deactivatePoint'): void {
    if (!this.context.canManageOperator() || this.lifecycleSubmitting) {
      return;
    }
    this.actionError = null;
    this.pendingPoint = point;
    this.pendingAction = action;
  }

  cancelLifecycle(): void {
    if (!this.lifecycleSubmitting) {
      this.pendingAction = null;
      this.pendingPoint = null;
    }
  }

  confirmLifecycle(): void {
    const action = this.pendingAction;
    const operatorId = this.context.selectedOperatorId();
    const routeId = this.route.snapshot.paramMap.get('routeId');
    if (
      !action ||
      !operatorId ||
      !routeId ||
      !this.routeDetail ||
      this.routeDetail.operatorId !== operatorId ||
      !this.context.canManageOperator()
    ) {
      this.pendingAction = null;
      this.pendingPoint = null;
      return;
    }

    let request: Observable<OperatorRoute | OperatorRoutePoint>;
    switch (action) {
      case 'activate':
        request = this.api.activateRoute(operatorId, routeId);
        break;
      case 'deactivate':
        request = this.api.deactivateRoute(operatorId, routeId);
        break;
      case 'activatePoint':
        if (!this.pendingPoint) {
          this.pendingAction = null;
          return;
        }
        request = this.api.activateRoutePoint(operatorId, routeId, this.pendingPoint.id);
        break;
      case 'deactivatePoint':
        if (!this.pendingPoint) {
          this.pendingAction = null;
          return;
        }
        request = this.api.deactivateRoutePoint(operatorId, routeId, this.pendingPoint.id);
        break;
    }

    this.lifecycleSubmitting = true;
    this.actionError = null;
    request.subscribe({
      next: (result) => {
        this.lifecycleSubmitting = false;
        this.pendingAction = null;
        this.pendingPoint = null;
        if (action === 'activate' || action === 'deactivate') {
          this.routeDetail = result as OperatorRoute;
          this.successMessage =
            action === 'activate' ? 'Route activated successfully.' : 'Route deactivated successfully.';
          return;
        }
        this.applyPoint(result as OperatorRoutePoint);
        this.successMessage =
          action === 'activatePoint' ? 'Point activated successfully.' : 'Point deactivated successfully.';
      },
      error: (error: unknown) => {
        this.lifecycleSubmitting = false;
        const actionError = readOperatorRouteActionError(error);
        if (actionError) {
          this.actionError = actionError;
          this.pendingAction = null;
          this.pendingPoint = null;
          return;
        }
        this.routeDetail = null;
        this.pendingAction = null;
        this.pendingPoint = null;
        this.error = this.errors.handle(error);
      }
    });
  }

  lifecycleActionLabel(action: RouteLifecycleAction): string {
    switch (action) {
      case 'activate':
        return 'Activate route';
      case 'deactivate':
        return 'Deactivate route';
      case 'activatePoint':
        return 'Activate point';
      case 'deactivatePoint':
        return 'Deactivate point';
    }
  }

  lifecycleConfirmationMessage(action: RouteLifecycleAction): string {
    switch (action) {
      case 'activate':
        return 'Activate this route so it can be used for new trips?';
      case 'deactivate':
        return 'Deactivate this route? Existing trips will not be changed.';
      case 'activatePoint':
        return `Activate boarding/dropping point “${this.pendingPoint?.name ?? ''}”?`;
      case 'deactivatePoint':
        return `Deactivate boarding/dropping point “${this.pendingPoint?.name ?? ''}”? Existing trips will not be changed.`;
    }
  }

  @HostListener('document:keydown.escape')
  closeConfirmationOnEscape(): void {
    this.cancelLifecycle();
  }

  private applyPoint(updated: OperatorRoutePoint): void {
    if (!this.routeDetail) {
      return;
    }
    this.routeDetail = {
      ...this.routeDetail,
      stops: this.routeDetail.stops.map((stop) =>
        stop.id === updated.routeStopId
          ? {
              ...stop,
              points: stop.points.map((point) => (point.id === updated.id ? updated : point))
            }
          : stop
      )
    };
  }
}

export type RouteLifecycleAction =
  | 'activate'
  | 'deactivate'
  | 'activatePoint'
  | 'deactivatePoint';
