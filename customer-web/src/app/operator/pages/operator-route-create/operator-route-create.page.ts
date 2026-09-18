import { Component, OnInit, inject } from '@angular/core';
import {
  FormArray,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { CustomerLocation } from '../../../core/api/models';
import { LocationsService } from '../../../core/api/locations.service';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import {
  OperatorRouteActionError,
  readOperatorRouteActionError
} from '../../components/operator-route-errors';
import {
  ROUTE_POINT_TYPES,
  ROUTE_STOP_KINDS,
  differentLocationsValidator,
  stopTimingValidator,
  toPointMutationRequest,
  toStopCreateRequest
} from '../../components/operator-route-references';
import {
  CreateOperatorRoutePointRequest,
  CreateOperatorRouteRequest,
  CreateOperatorRouteStopRequest,
  OperatorStopKind
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-route-create-page',
  imports: [ReactiveFormsModule, RouterLink, EmptyStateComponent],
  templateUrl: './operator-route-create.page.html'
})
export class OperatorRouteCreatePageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  private readonly locationsApi = inject(LocationsService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly router = inject(Router);

  readonly stopKinds = ROUTE_STOP_KINDS;
  readonly pointTypes = ROUTE_POINT_TYPES;
  readonly form = new FormGroup(
    {
      code: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(60)]
      }),
      name: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(160)]
      }),
      sourceLocationId: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required]
      }),
      destinationLocationId: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required]
      }),
      stops: new FormArray<FormGroup>([])
    },
    { validators: [differentLocationsValidator('sourceLocationId', 'destinationLocationId')] }
  );

  loading = true;
  submitting = false;
  pageError: OperatorPageError | null = null;
  actionError: OperatorRouteActionError | null = null;
  locations: CustomerLocation[] = [];
  private loadVersion = 0;

  get stops(): FormArray<FormGroup> {
    return this.form.controls.stops;
  }

  ngOnInit(): void {
    this.loadReferenceData();
  }

  loadReferenceData(): void {
    const operatorId = this.context.selectedOperatorId();
    const version = ++this.loadVersion;
    this.locations = [];
    this.actionError = null;
    if (!operatorId || !this.context.canManageOperator()) {
      this.showWriteAccessDenied();
      return;
    }
    this.loading = true;
    this.pageError = null;
    this.locationsApi.list().subscribe({
      next: (locations) => {
        if (version !== this.loadVersion) {
          return;
        }
        this.locations = locations;
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

  addStop(): void {
    const nextSequence =
      this.stops.controls.reduce((max, stop) => {
        const sequence = Number(stop.controls['sequenceNumber'].value) || 0;
        return Math.max(max, sequence);
      }, 0) + 1;
    this.stops.push(this.createStopGroup(nextSequence));
  }

  removeStop(index: number): void {
    this.stops.removeAt(index);
  }

  addPoint(stopIndex: number): void {
    this.pointsOf(stopIndex).push(this.createPointGroup());
  }

  removePoint(stopIndex: number, pointIndex: number): void {
    this.pointsOf(stopIndex).removeAt(pointIndex);
  }

  pointsOf(stopIndex: number): FormArray<FormGroup> {
    return this.stops.at(stopIndex).controls['points'] as FormArray<FormGroup>;
  }

  submit(): void {
    this.actionError = null;
    const code = this.form.controls.code.value.trim();
    const name = this.form.controls.name.value.trim();
    this.form.controls.code.setValue(code);
    this.form.controls.name.setValue(name);
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting) {
      return;
    }

    const operatorId = this.context.selectedOperatorId();
    if (!operatorId || !this.context.canManageOperator()) {
      this.showWriteAccessDenied();
      return;
    }

    const value = this.form.getRawValue();
    const request: CreateOperatorRouteRequest = {
      code,
      name,
      sourceLocationId: value.sourceLocationId,
      destinationLocationId: value.destinationLocationId
    };
    const stops = this.buildStops();
    if (stops.length > 0) {
      request.stops = stops;
    }

    this.submitting = true;
    this.api.createRoute(operatorId, request).subscribe({
      next: (route) => {
        this.submitting = false;
        void this.router.navigate(['/operator', operatorId, 'routes', route.id], {
          queryParams: { created: 'true' }
        });
      },
      error: (error: unknown) => {
        this.submitting = false;
        const actionError = readOperatorRouteActionError(error);
        if (actionError) {
          this.actionError = actionError;
          return;
        }
        this.pageError = this.errors.handle(error);
      }
    });
  }

  private buildStops(): CreateOperatorRouteStopRequest[] {
    return this.stops.controls.map((stop) => {
      const value = stop.getRawValue() as {
        locationId: string;
        sequenceNumber: number;
        stopKind: OperatorStopKind;
        arrivalOffsetMinutes: number | null;
        departureOffsetMinutes: number | null;
        distanceKm: number | null;
        points: Array<{
          name: string;
          pointType: CreateOperatorRoutePointRequest['pointType'];
          address: string;
          latitude: number | null;
          longitude: number | null;
        }>;
      };
      const points = value.points
        .map((point) => toPointMutationRequest(point))
        .filter((point) => point.name.length > 0);
      return toStopCreateRequest({
        locationId: value.locationId,
        sequenceNumber: Number(value.sequenceNumber),
        stopKind: value.stopKind,
        arrivalOffsetMinutes: value.arrivalOffsetMinutes,
        departureOffsetMinutes: value.departureOffsetMinutes,
        distanceKm: value.distanceKm,
        points
      });
    });
  }

  private createStopGroup(sequenceNumber: number): FormGroup {
    return new FormGroup(
      {
        locationId: new FormControl('', {
          nonNullable: true,
          validators: [Validators.required]
        }),
        sequenceNumber: new FormControl(sequenceNumber, {
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
        }),
        points: new FormArray<FormGroup>([])
      },
      { validators: [stopTimingValidator()] }
    );
  }

  private createPointGroup(): FormGroup {
    return new FormGroup({
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
  }

  private showWriteAccessDenied(): void {
    this.loading = false;
    this.pageError = {
      kind: 'forbidden',
      title: 'Operator admin access required',
      message: 'Only an operator admin can create routes.'
    };
  }
}
