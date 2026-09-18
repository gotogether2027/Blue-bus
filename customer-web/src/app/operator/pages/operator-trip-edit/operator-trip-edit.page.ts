import { Component, OnInit, inject } from '@angular/core';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { EMPTY, forkJoin, of, switchMap } from 'rxjs';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import {
  OperatorTripActionError,
  readOperatorTripActionError
} from '../../components/operator-trip-errors';
import {
  DEFAULT_TRIP_TIME_ZONE,
  busSummary,
  canEditTripCommercialTerms,
  isoFromWallClock,
  routeSummary,
  tripCommercialTimingValidator,
  wallClockFromIso
} from '../../components/operator-trip-references';
import {
  OperatorBus,
  OperatorRoute,
  OperatorTrip,
  UpdateOperatorTripRequest
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-trip-edit-page',
  imports: [ReactiveFormsModule, RouterLink, EmptyStateComponent],
  templateUrl: './operator-trip-edit.page.html'
})
export class OperatorTripEditPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly form = new FormGroup(
    {
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
      })
    },
    {
      validators: [
        tripCommercialTimingValidator(
          () => this.trip?.scheduledDepartureAt ?? null,
          () => this.trip?.timeZone ?? DEFAULT_TRIP_TIME_ZONE
        )
      ]
    }
  );

  loading = true;
  submitting = false;
  pageError: OperatorPageError | null = null;
  actionError: OperatorTripActionError | null = null;
  trip: OperatorTrip | null = null;
  bus: OperatorBus | null = null;
  routeDetail: OperatorRoute | null = null;
  private loadVersion = 0;
  readonly busSummary = busSummary;
  readonly routeSummary = routeSummary;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const tripId = this.route.snapshot.paramMap.get('tripId');
    const version = ++this.loadVersion;
    this.trip = null;
    this.bus = null;
    this.routeDetail = null;
    this.actionError = null;

    if (!operatorId || !tripId) {
      this.showNotFound();
      return;
    }
    if (!this.context.canManageOperator()) {
      this.showWriteAccessDenied();
      return;
    }

    this.loading = true;
    this.pageError = null;
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
            route: this.api.getRoute(operatorId, trip.routeId)
          });
        })
      )
      .subscribe({
        next: ({ trip, bus, route }) => {
          if (version !== this.loadVersion) {
            return;
          }
          if (trip.operatorId !== operatorId) {
            this.showNotFound('This trip does not belong to the selected operator.');
            return;
          }
          if (!canEditTripCommercialTerms(trip.status)) {
            this.loading = false;
            this.pageError = {
              kind: 'request',
              title: 'Commercial terms are locked',
              message:
                'Trip commercial terms can only be updated while the trip is DRAFT or SCHEDULED.'
            };
            return;
          }
          this.trip = trip;
          this.bus = bus;
          this.routeDetail = route;
          this.form.reset({
            baseFare: trip.baseFare,
            bookingOpensAt: wallClockFromIso(trip.bookingOpensAt, trip.timeZone),
            bookingClosesAt: wallClockFromIso(trip.bookingClosesAt, trip.timeZone)
          });
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
    if (this.form.invalid || this.submitting || !this.trip) {
      return;
    }

    const operatorId = this.context.selectedOperatorId();
    const tripId = this.route.snapshot.paramMap.get('tripId');
    if (
      !operatorId ||
      !tripId ||
      !this.context.canManageOperator() ||
      this.trip.operatorId !== operatorId
    ) {
      this.showWriteAccessDenied();
      return;
    }

    const value = this.form.getRawValue();
    if (value.baseFare === null) {
      return;
    }
    const timeZone = this.trip.timeZone || DEFAULT_TRIP_TIME_ZONE;
    const bookingOpensAt = isoFromWallClock(value.bookingOpensAt, timeZone);
    const bookingClosesAt = isoFromWallClock(value.bookingClosesAt, timeZone);
    if (!bookingOpensAt || !bookingClosesAt) {
      this.actionError = {
        title: 'Check the trip details',
        message: 'Enter valid booking window date and time values.'
      };
      return;
    }

    const request: UpdateOperatorTripRequest = {};
    if (value.baseFare !== this.trip.baseFare) {
      request.baseFare = value.baseFare;
    }
    if (bookingOpensAt !== new Date(this.trip.bookingOpensAt).toISOString()) {
      request.bookingOpensAt = bookingOpensAt;
    }
    if (bookingClosesAt !== new Date(this.trip.bookingClosesAt).toISOString()) {
      request.bookingClosesAt = bookingClosesAt;
    }

    if (Object.keys(request).length === 0) {
      this.actionError = {
        title: 'No changes to save',
        message: 'Update at least one commercial field before saving.'
      };
      return;
    }

    this.submitting = true;
    this.api.updateTrip(operatorId, tripId, request).subscribe({
      next: () => {
        this.submitting = false;
        void this.router.navigate(['/operator', operatorId, 'trips', tripId], {
          queryParams: { updated: 'true' }
        });
      },
      error: (error: unknown) => {
        this.submitting = false;
        const actionError = readOperatorTripActionError(error);
        if (actionError) {
          this.actionError = actionError;
          return;
        }
        this.trip = null;
        this.pageError = this.errors.handle(error);
      }
    });
  }

  private showNotFound(message = 'The requested trip could not be identified.'): void {
    this.loading = false;
    this.pageError = {
      kind: 'not-found',
      title: 'Trip not found',
      message
    };
  }

  private showWriteAccessDenied(): void {
    this.loading = false;
    this.pageError = {
      kind: 'forbidden',
      title: 'Operator admin access required',
      message: 'Only an operator admin can edit trip commercial terms.'
    };
  }
}
