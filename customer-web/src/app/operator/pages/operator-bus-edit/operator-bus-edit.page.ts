import { Component, OnInit, inject } from '@angular/core';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { OperatorRetryButtonComponent } from '../../components/operator-retry-button';
import {
  OperatorBusActionError,
  readOperatorBusActionError
} from '../../components/operator-bus-errors';
import {
  eligibleActiveBusTypes,
  eligiblePublishedSeatLayouts
} from '../../components/operator-bus-references';
import {
  OperatorBus,
  OperatorBusType,
  OperatorSeatLayout,
  UpdateOperatorBusRequest
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-bus-edit-page',
  imports: [ReactiveFormsModule, RouterLink, EmptyStateComponent, OperatorRetryButtonComponent],
  templateUrl: './operator-bus-edit.page.html'
})
export class OperatorBusEditPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly form = new FormGroup({
    displayName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(120)]
    }),
    busTypeId: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required]
    }),
    seatLayoutId: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required]
    })
  });

  loading = true;
  submitting = false;
  pageError: OperatorPageError | null = null;
  actionError: OperatorBusActionError | null = null;
  bus: OperatorBus | null = null;
  busTypes: OperatorBusType[] = [];
  seatLayouts: OperatorSeatLayout[] = [];
  private loadVersion = 0;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const busId = this.route.snapshot.paramMap.get('busId');
    const version = ++this.loadVersion;
    this.bus = null;
    this.busTypes = [];
    this.seatLayouts = [];
    this.actionError = null;

    if (!operatorId || !busId) {
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
      bus: this.api.getBus(operatorId, busId),
      busTypes: this.api.listActiveBusTypes(operatorId),
      seatLayouts: this.api.listPublishedSeatLayouts(operatorId)
    }).subscribe({
      next: ({ bus, busTypes, seatLayouts }) => {
        if (version !== this.loadVersion) {
          return;
        }
        this.bus = bus;
        this.busTypes = eligibleActiveBusTypes(busTypes);
        this.seatLayouts = eligiblePublishedSeatLayouts(seatLayouts, operatorId);
        this.form.reset({
          displayName: bus.displayName ?? '',
          busTypeId: bus.busTypeId,
          seatLayoutId: bus.seatLayoutId
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
    if (this.form.invalid || this.submitting || !this.bus) {
      return;
    }

    const operatorId = this.context.selectedOperatorId();
    const busId = this.route.snapshot.paramMap.get('busId');
    if (
      !operatorId ||
      !busId ||
      !this.context.canManageOperator() ||
      this.bus.operatorId !== operatorId
    ) {
      this.showWriteAccessDenied();
      return;
    }

    const value = this.form.getRawValue();
    const displayName = value.displayName.trim();
    const request: UpdateOperatorBusRequest = {};
    if (displayName !== (this.bus.displayName ?? '')) {
      request.displayName = displayName;
    }
    if (value.busTypeId !== this.bus.busTypeId) {
      request.busTypeId = value.busTypeId;
    }
    if (value.seatLayoutId !== this.bus.seatLayoutId) {
      request.seatLayoutId = value.seatLayoutId;
    }

    if (Object.keys(request).length === 0) {
      this.actionError = {
        title: 'No changes to save',
        message: 'Update at least one editable bus field before saving.'
      };
      return;
    }

    this.submitting = true;
    this.api.updateBus(operatorId, busId, request).subscribe({
      next: () => {
        this.submitting = false;
        void this.router.navigate(['/operator', operatorId, 'buses', busId], {
          queryParams: { updated: 'true' }
        });
      },
      error: (error: unknown) => {
        this.submitting = false;
        const actionError = readOperatorBusActionError(error);
        if (actionError) {
          this.actionError = actionError;
          return;
        }
        this.bus = null;
        this.pageError = this.errors.handle(error);
      }
    });
  }

  hasActiveBusType(busTypeId: string): boolean {
    return this.busTypes.some((busType) => busType.id === busTypeId);
  }

  hasPublishedSeatLayout(seatLayoutId: string): boolean {
    return this.seatLayouts.some((layout) => layout.id === seatLayoutId);
  }

  private showNotFound(): void {
    this.loading = false;
    this.pageError = {
      kind: 'not-found',
      title: 'Bus not found',
      message: 'The requested bus could not be identified.'
    };
  }

  private showWriteAccessDenied(): void {
    this.loading = false;
    this.pageError = {
      kind: 'forbidden',
      title: 'Operator admin access required',
      message: 'Only an operator admin can edit buses.'
    };
  }
}
