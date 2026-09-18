import { Component, OnInit, inject } from '@angular/core';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { OperatorRetryButtonComponent } from '../../components/operator-retry-button';
import {
  OperatorTripActionError,
  readOperatorTripActionError
} from '../../components/operator-trip-errors';
import {
  DEFAULT_TRIP_TIME_ZONE,
  eligibleActiveBuses,
  eligibleActiveRoutesForTrip,
  isoFromWallClock,
  tripCreateTimingValidator
} from '../../components/operator-trip-references';
import {
  CreateOperatorTripRequest,
  OperatorBus,
  OperatorRoute
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-trip-create-page',
  imports: [ReactiveFormsModule, RouterLink, EmptyStateComponent, OperatorRetryButtonComponent],
  templateUrl: './operator-trip-create.page.html'
})
export class OperatorTripCreatePageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly router = inject(Router);

  readonly form = new FormGroup(
    {
      busId: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required]
      }),
      routeId: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required]
      }),
      scheduledDepartureAt: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required]
      }),
      scheduledArrivalAt: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required]
      }),
      baseFare: new FormControl<number | null>(null, {
        validators: [Validators.required, Validators.min(0)]
      }),
      bookingOpensAt: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required]
      }),
      bookingClosesAt: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required]
      }),
      timeZone: new FormControl(DEFAULT_TRIP_TIME_ZONE, {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(64)]
      })
    },
    { validators: [tripCreateTimingValidator()] }
  );

  loading = true;
  submitting = false;
  pageError: OperatorPageError | null = null;
  actionError: OperatorTripActionError | null = null;
  buses: OperatorBus[] = [];
  routes: OperatorRoute[] = [];
  private loadVersion = 0;

  ngOnInit(): void {
    this.loadReferenceData();
  }

  loadReferenceData(): void {
    const operatorId = this.context.selectedOperatorId();
    const version = ++this.loadVersion;
    this.buses = [];
    this.routes = [];
    this.actionError = null;

    if (!operatorId || !this.context.canManageOperator()) {
      this.showWriteAccessDenied();
      return;
    }

    this.loading = true;
    this.pageError = null;
    forkJoin({
      buses: this.api.listBuses(operatorId),
      routes: this.api.listRoutes(operatorId, 'ACTIVE')
    }).subscribe({
      next: ({ buses, routes }) => {
        if (version !== this.loadVersion) {
          return;
        }
        this.buses = eligibleActiveBuses(buses, operatorId);
        this.routes = eligibleActiveRoutesForTrip(routes, operatorId);
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

  submit(): void {
    this.actionError = null;
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
    const timeZone = value.timeZone.trim() || DEFAULT_TRIP_TIME_ZONE;
    const scheduledDepartureAt = isoFromWallClock(value.scheduledDepartureAt, timeZone);
    const scheduledArrivalAt = isoFromWallClock(value.scheduledArrivalAt, timeZone);
    const bookingOpensAt = isoFromWallClock(value.bookingOpensAt, timeZone);
    const bookingClosesAt = isoFromWallClock(value.bookingClosesAt, timeZone);
    if (!scheduledDepartureAt || !scheduledArrivalAt || !bookingOpensAt || !bookingClosesAt) {
      this.actionError = {
        title: 'Check the trip details',
        message: 'Enter valid date and time values in the selected time zone.'
      };
      return;
    }
    if (value.baseFare === null) {
      return;
    }

    const request: CreateOperatorTripRequest = {
      busId: value.busId,
      routeId: value.routeId,
      scheduledDepartureAt,
      scheduledArrivalAt,
      baseFare: value.baseFare,
      bookingOpensAt,
      bookingClosesAt,
      timeZone
    };

    this.submitting = true;
    this.api.createTrip(operatorId, request).subscribe({
      next: (trip) => {
        this.submitting = false;
        void this.router.navigate(['/operator', operatorId, 'trips', trip.id], {
          queryParams: { created: 'true' }
        });
      },
      error: (error: unknown) => {
        this.submitting = false;
        const actionError = readOperatorTripActionError(error);
        if (actionError) {
          this.actionError = actionError;
          return;
        }
        this.pageError = this.errors.handle(error);
      }
    });
  }

  private showWriteAccessDenied(): void {
    this.loading = false;
    this.pageError = {
      kind: 'forbidden',
      title: 'Operator admin access required',
      message: 'Only an operator admin can create trips.'
    };
  }
}
