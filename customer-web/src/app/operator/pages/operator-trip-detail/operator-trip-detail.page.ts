import { Component, HostListener, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EMPTY, Observable, forkJoin, of, switchMap } from 'rxjs';
import { CustomerLocation } from '../../../core/api/models';
import { LocationsService } from '../../../core/api/locations.service';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { formatDate, formatInstant, formatMoney } from '../../../shared/format';
import { operatorLocationLabel } from '../../components/operator-route-references';
import { operatorStatusTone } from '../../components/operator-status';
import {
  OperatorTripActionError,
  readOperatorTripActionError
} from '../../components/operator-trip-errors';
import { OperatorTripOpsNavComponent } from '../../components/operator-trip-ops-nav';
import {
  busSummary,
  canCancelTrip,
  canEditTripCommercialTerms,
  canScheduleTrip,
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
  selector: 'app-operator-trip-detail-page',
  imports: [RouterLink, EmptyStateComponent, StatusBadgeComponent, OperatorTripOpsNavComponent],
  templateUrl: './operator-trip-detail.page.html'
})
export class OperatorTripDetailPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  private readonly locationsApi = inject(LocationsService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private loadVersion = 0;

  loading = true;
  error: OperatorPageError | null = null;
  trip: OperatorTrip | null = null;
  bus: OperatorBus | null = null;
  routeDetail: OperatorRoute | null = null;
  locations: CustomerLocation[] = [];
  actionError: OperatorTripActionError | null = null;
  successMessage: string | null = null;
  pendingLifecycleAction: TripLifecycleAction | null = null;
  lifecycleSubmitting = false;
  readonly formatDate = formatDate;
  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;
  readonly statusTone = operatorStatusTone;
  readonly canScheduleTrip = canScheduleTrip;
  readonly canCancelTrip = canCancelTrip;
  readonly canEditTripCommercialTerms = canEditTripCommercialTerms;

  ngOnInit(): void {
    if (this.route.snapshot.queryParamMap.get('created') === 'true') {
      this.successMessage = 'Trip created as a DRAFT. Scheduling is a separate lifecycle action.';
    } else if (this.route.snapshot.queryParamMap.get('updated') === 'true') {
      this.successMessage = 'Trip commercial terms updated successfully.';
    }
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const tripId = this.route.snapshot.paramMap.get('tripId');
    const version = ++this.loadVersion;
    this.trip = null;
    this.bus = null;
    this.routeDetail = null;
    this.locations = [];
    this.actionError = null;
    this.pendingLifecycleAction = null;
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
        switchMap((trip) => {
          if (version !== this.loadVersion) {
            return EMPTY;
          }
          return forkJoin({
            trip: of(trip),
            bus: this.api.getBus(operatorId, trip.busId),
            route: this.api.getRoute(operatorId, trip.routeId),
            locations: this.locationsApi.list()
          });
        })
      )
      .subscribe({
        next: ({ trip, bus, route, locations }) => {
          if (version !== this.loadVersion) {
            return;
          }
          if (trip.operatorId !== operatorId) {
            this.loading = false;
            this.error = {
              kind: 'not-found',
              title: 'Trip not found',
              message: 'This trip does not belong to the selected operator.'
            };
            return;
          }
          this.trip = trip;
          this.bus = bus;
          this.routeDetail = route;
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

  busLabel(): string {
    return busSummary(this.bus ?? undefined, this.trip?.busId ?? '');
  }

  routeLabel(): string {
    return routeSummary(this.routeDetail ?? undefined, this.trip?.routeId ?? '');
  }

  stopLocationLabel(locationId: string): string {
    return operatorLocationLabel(this.locations, locationId);
  }

  requestLifecycle(action: TripLifecycleAction): void {
    if (!this.context.canManageOperator() || this.lifecycleSubmitting || !this.trip) {
      return;
    }
    if (action === 'schedule' && !canScheduleTrip(this.trip.status)) {
      return;
    }
    if (action === 'cancel' && !canCancelTrip(this.trip.status)) {
      return;
    }
    this.actionError = null;
    this.pendingLifecycleAction = action;
  }

  cancelLifecycle(): void {
    if (!this.lifecycleSubmitting) {
      this.pendingLifecycleAction = null;
    }
  }

  confirmLifecycle(): void {
    const action = this.pendingLifecycleAction;
    const operatorId = this.context.selectedOperatorId();
    const tripId = this.route.snapshot.paramMap.get('tripId');
    if (
      !action ||
      !operatorId ||
      !tripId ||
      !this.trip ||
      this.trip.operatorId !== operatorId ||
      !this.context.canManageOperator()
    ) {
      this.pendingLifecycleAction = null;
      return;
    }

    const request: Observable<OperatorTrip> =
      action === 'schedule'
        ? this.api.scheduleTrip(operatorId, tripId)
        : this.api.cancelTrip(operatorId, tripId);

    this.lifecycleSubmitting = true;
    this.actionError = null;
    request.subscribe({
      next: (trip) => {
        this.trip = trip;
        this.lifecycleSubmitting = false;
        this.pendingLifecycleAction = null;
        this.successMessage =
          action === 'schedule'
            ? 'Trip scheduled successfully.'
            : 'Trip cancelled successfully.';
      },
      error: (error: unknown) => {
        this.lifecycleSubmitting = false;
        const actionError = readOperatorTripActionError(error);
        if (actionError) {
          this.actionError = actionError;
          this.pendingLifecycleAction = null;
          return;
        }
        this.trip = null;
        this.bus = null;
        this.routeDetail = null;
        this.pendingLifecycleAction = null;
        this.error = this.errors.handle(error);
      }
    });
  }

  lifecycleActionLabel(action: TripLifecycleAction): string {
    return action === 'schedule' ? 'Schedule trip' : 'Cancel trip';
  }

  lifecycleConfirmationMessage(action: TripLifecycleAction): string {
    if (action === 'schedule') {
      return 'Scheduling is a lifecycle transition from DRAFT. It does not change the stored departure or arrival times.';
    }
    return 'Cancellation is a lifecycle action and cannot be casually reversed. The backend applies its own passenger and refund rules.';
  }

  @HostListener('document:keydown.escape')
  closeConfirmationOnEscape(): void {
    this.cancelLifecycle();
  }
}

export type TripLifecycleAction = 'schedule' | 'cancel';
