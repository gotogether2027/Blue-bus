import { Component, OnInit, inject } from '@angular/core';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators
} from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { CustomerLocation } from '../../../core/api/models';
import { LocationsService } from '../../../core/api/locations.service';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { OperatorRetryButtonComponent } from '../../components/operator-retry-button';
import {
  OperatorRouteActionError,
  readOperatorRouteActionError
} from '../../components/operator-route-errors';
import {
  ROUTE_POINT_TYPES,
  ROUTE_STOP_KINDS,
  STRUCTURAL_ROUTE_RESTRICTION,
  nextRouteStopSequence,
  operatorLocationLabel,
  orderedRouteStops,
  routeHasTrips,
  stopTimingValidator,
  toPointMutationRequest,
  toStopCreateRequest,
  toStopUpdateRequest
} from '../../components/operator-route-references';
import {
  CreateOperatorRoutePointRequest,
  OperatorRoute,
  OperatorRoutePoint,
  OperatorRouteStop,
  OperatorStopKind
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-route-stops-page',
  imports: [ReactiveFormsModule, RouterLink, EmptyStateComponent, OperatorRetryButtonComponent],
  templateUrl: './operator-route-stops.page.html'
})
export class OperatorRouteStopsPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  private readonly locationsApi = inject(LocationsService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);

  readonly stopKinds = ROUTE_STOP_KINDS;
  readonly pointTypes = ROUTE_POINT_TYPES;
  readonly structuralRestriction = STRUCTURAL_ROUTE_RESTRICTION;
  readonly stopForm = new FormGroup(
    {
      locationId: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required]
      }),
      sequenceNumber: new FormControl(1, {
        nonNullable: true,
        validators: [Validators.required, Validators.min(1)]
      }),
      stopKind: new FormControl<OperatorStopKind>('INTERMEDIATE', {
        nonNullable: true,
        validators: [Validators.required]
      }),
      arrivalOffsetMinutes: new FormControl<number | null>(null, {
        validators: [Validators.min(0)]
      }),
      departureOffsetMinutes: new FormControl<number | null>(null, {
        validators: [Validators.min(0)]
      }),
      distanceKm: new FormControl<number | null>(null, {
        validators: [Validators.min(0)]
      })
    },
    { validators: [stopTimingValidator()] }
  );
  readonly pointForm = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(160)]
    }),
    pointType: new FormControl<CreateOperatorRoutePointRequest['pointType']>('BOARDING', {
      nonNullable: true,
      validators: [Validators.required]
    }),
    address: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(255)]
    }),
    latitude: new FormControl<number | null>(null, {
      validators: [Validators.min(-90), Validators.max(90)]
    }),
    longitude: new FormControl<number | null>(null, {
      validators: [Validators.min(-180), Validators.max(180)]
    })
  });

  loading = true;
  stopSubmitting = false;
  pointSubmitting = false;
  pageError: OperatorPageError | null = null;
  actionError: OperatorRouteActionError | null = null;
  successMessage: string | null = null;
  routeDetail: OperatorRoute | null = null;
  locations: CustomerLocation[] = [];
  hasTrips = false;
  selectedStopId: string | null = null;
  editingPointId: string | null = null;
  private loadVersion = 0;

  get orderedStops(): OperatorRouteStop[] {
    return orderedRouteStops(this.routeDetail?.stops ?? []);
  }

  get selectedStop(): OperatorRouteStop | null {
    return this.orderedStops.find((stop) => stop.id === this.selectedStopId) ?? null;
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const routeId = this.route.snapshot.paramMap.get('routeId');
    const version = ++this.loadVersion;
    this.routeDetail = null;
    this.locations = [];
    this.hasTrips = false;
    this.actionError = null;
    if (!operatorId || !routeId) {
      this.showNotFound();
      return;
    }
    if (!this.context.canManageOperator()) {
      this.showWriteAccessDenied();
      return;
    }

    this.loading = true;
    this.pageError = null;
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
        const selectedStillExists = this.orderedStops.some((stop) => stop.id === this.selectedStopId);
        if (!selectedStillExists) {
          this.selectedStopId = this.orderedStops[0]?.id ?? null;
          this.editingPointId = null;
        }
        this.resetStopForm();
        this.resetPointForm();
        this.loading = false;
      },
      error: (error: unknown) => {
        if (version !== this.loadVersion) {
          return;
        }
        this.loading = false;
        this.pageError = this.errors.handle(error);
      }
    });
  }

  locationLabel(locationId: string): string {
    return operatorLocationLabel(this.locations, locationId);
  }

  selectStop(stopId: string): void {
    this.selectedStopId = stopId;
    this.editingPointId = null;
    this.actionError = null;
    this.resetStopForm();
    this.resetPointForm();
  }

  startAddStop(): void {
    this.selectedStopId = null;
    this.editingPointId = null;
    this.actionError = null;
    this.resetStopForm();
    this.resetPointForm();
  }

  startEditPoint(point: OperatorRoutePoint): void {
    this.editingPointId = point.id;
    this.pointForm.reset({
      name: point.name,
      pointType: point.pointType,
      address: point.address ?? '',
      latitude: point.latitude,
      longitude: point.longitude
    });
  }

  cancelEditPoint(): void {
    this.editingPointId = null;
    this.resetPointForm();
  }

  submitStop(): void {
    this.actionError = null;
    this.stopForm.markAllAsTouched();
    if (this.stopForm.invalid || this.stopSubmitting || this.hasTrips || !this.routeDetail) {
      return;
    }
    const operatorId = this.context.selectedOperatorId();
    const routeId = this.route.snapshot.paramMap.get('routeId');
    if (!operatorId || !routeId || !this.context.canManageOperator()) {
      this.showWriteAccessDenied();
      return;
    }

    const value = this.stopForm.getRawValue();
    const payload = {
      locationId: value.locationId,
      sequenceNumber: Number(value.sequenceNumber),
      stopKind: value.stopKind,
      arrivalOffsetMinutes: value.arrivalOffsetMinutes,
      departureOffsetMinutes: value.departureOffsetMinutes,
      distanceKm: value.distanceKm
    };
    this.stopSubmitting = true;
    const request$ = this.selectedStop
      ? this.api.updateRouteStop(
          operatorId,
          routeId,
          this.selectedStop.id,
          toStopUpdateRequest(payload)
        )
      : this.api.addRouteStop(operatorId, routeId, toStopCreateRequest(payload));

    request$.subscribe({
      next: (stop) => {
        this.stopSubmitting = false;
        this.successMessage = this.selectedStop ? 'Stop updated successfully.' : 'Stop added successfully.';
        this.selectedStopId = stop.id;
        this.load();
      },
      error: (error: unknown) => this.handleMutationError(error, 'stop')
    });
  }

  submitPoint(): void {
    this.actionError = null;
    this.pointForm.markAllAsTouched();
    if (
      this.pointForm.invalid ||
      this.pointSubmitting ||
      this.hasTrips ||
      !this.routeDetail ||
      !this.selectedStop
    ) {
      return;
    }
    const operatorId = this.context.selectedOperatorId();
    const routeId = this.route.snapshot.paramMap.get('routeId');
    if (!operatorId || !routeId || !this.context.canManageOperator()) {
      this.showWriteAccessDenied();
      return;
    }

    const request = toPointMutationRequest(this.pointForm.getRawValue());
    this.pointSubmitting = true;
    const request$ = this.editingPointId
      ? this.api.updateRoutePoint(
          operatorId,
          routeId,
          this.selectedStop.id,
          this.editingPointId,
          request
        )
      : this.api.addRoutePoint(operatorId, routeId, this.selectedStop.id, request);

    request$.subscribe({
      next: () => {
        this.pointSubmitting = false;
        this.successMessage = this.editingPointId
          ? 'Point updated successfully.'
          : 'Point added successfully.';
        this.editingPointId = null;
        this.load();
      },
      error: (error: unknown) => this.handleMutationError(error, 'point')
    });
  }

  private resetStopForm(): void {
    const selected = this.selectedStop;
    this.stopForm.reset({
      locationId: selected?.locationId ?? '',
      sequenceNumber: selected?.sequenceNumber ?? nextRouteStopSequence(this.orderedStops),
      stopKind: selected?.stopKind ?? 'INTERMEDIATE',
      arrivalOffsetMinutes: selected?.arrivalOffsetMinutes ?? null,
      departureOffsetMinutes: selected?.departureOffsetMinutes ?? null,
      distanceKm: selected?.distanceKm ?? null
    });
  }

  private resetPointForm(): void {
    this.pointForm.reset({
      name: '',
      pointType: 'BOARDING',
      address: '',
      latitude: null,
      longitude: null
    });
  }

  private handleMutationError(error: unknown, kind: 'stop' | 'point'): void {
    if (kind === 'stop') {
      this.stopSubmitting = false;
    } else {
      this.pointSubmitting = false;
    }
    const actionError = readOperatorRouteActionError(error);
    if (actionError) {
      this.actionError = actionError;
      return;
    }
    this.routeDetail = null;
    this.pageError = this.errors.handle(error);
  }

  private showNotFound(): void {
    this.loading = false;
    this.pageError = {
      kind: 'not-found',
      title: 'Route not found',
      message: 'The requested route could not be identified.'
    };
  }

  private showWriteAccessDenied(): void {
    this.loading = false;
    this.pageError = {
      kind: 'forbidden',
      title: 'Operator admin access required',
      message: 'Only an operator admin can manage route stops.'
    };
  }
}
