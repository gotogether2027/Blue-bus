import { Component, OnInit, inject } from '@angular/core';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { CustomerLocation } from '../../../core/api/models';
import { LocationsService } from '../../../core/api/locations.service';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import {
  OperatorRouteActionError,
  readOperatorRouteActionError
} from '../../components/operator-route-errors';
import {
  STRUCTURAL_ROUTE_RESTRICTION,
  differentLocationsValidator,
  routeHasTrips
} from '../../components/operator-route-references';
import { OperatorRoute, UpdateOperatorRouteRequest } from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-route-edit-page',
  imports: [ReactiveFormsModule, RouterLink, EmptyStateComponent],
  templateUrl: './operator-route-edit.page.html'
})
export class OperatorRouteEditPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  private readonly locationsApi = inject(LocationsService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly form = new FormGroup(
    {
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
      })
    },
    { validators: [differentLocationsValidator('sourceLocationId', 'destinationLocationId')] }
  );

  loading = true;
  submitting = false;
  pageError: OperatorPageError | null = null;
  actionError: OperatorRouteActionError | null = null;
  routeDetail: OperatorRoute | null = null;
  locations: CustomerLocation[] = [];
  hasTrips = false;
  readonly structuralRestriction = STRUCTURAL_ROUTE_RESTRICTION;
  private loadVersion = 0;

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
        this.form.reset({
          name: route.name,
          sourceLocationId: route.sourceLocationId,
          destinationLocationId: route.destinationLocationId
        });
        if (this.hasTrips) {
          this.form.controls.sourceLocationId.disable();
          this.form.controls.destinationLocationId.disable();
        } else {
          this.form.controls.sourceLocationId.enable();
          this.form.controls.destinationLocationId.enable();
        }
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

  hasActiveLocation(locationId: string): boolean {
    return this.locations.some((location) => location.id === locationId);
  }

  submit(): void {
    this.actionError = null;
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting || !this.routeDetail) {
      return;
    }

    const operatorId = this.context.selectedOperatorId();
    const routeId = this.route.snapshot.paramMap.get('routeId');
    if (
      !operatorId ||
      !routeId ||
      !this.context.canManageOperator() ||
      this.routeDetail.operatorId !== operatorId
    ) {
      this.showWriteAccessDenied();
      return;
    }

    const value = this.form.getRawValue();
    const name = value.name.trim();
    const request: UpdateOperatorRouteRequest = {};
    if (name !== this.routeDetail.name) {
      request.name = name;
    }
    if (!this.hasTrips && value.sourceLocationId !== this.routeDetail.sourceLocationId) {
      request.sourceLocationId = value.sourceLocationId;
    }
    if (!this.hasTrips && value.destinationLocationId !== this.routeDetail.destinationLocationId) {
      request.destinationLocationId = value.destinationLocationId;
    }

    if (Object.keys(request).length === 0) {
      this.actionError = {
        title: 'No changes to save',
        message: this.hasTrips
          ? 'Only the route name can be changed while trips exist, and it is unchanged.'
          : 'Update at least one editable route field before saving.'
      };
      return;
    }

    this.submitting = true;
    this.api.updateRoute(operatorId, routeId, request).subscribe({
      next: () => {
        this.submitting = false;
        void this.router.navigate(['/operator', operatorId, 'routes', routeId], {
          queryParams: { updated: 'true' }
        });
      },
      error: (error: unknown) => {
        this.submitting = false;
        const actionError = readOperatorRouteActionError(error);
        if (actionError) {
          this.actionError = actionError;
          return;
        }
        this.routeDetail = null;
        this.pageError = this.errors.handle(error);
      }
    });
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
      message: 'Only an operator admin can edit routes.'
    };
  }
}
