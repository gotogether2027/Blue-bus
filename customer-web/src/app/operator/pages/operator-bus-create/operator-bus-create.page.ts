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
  OperatorBusActionError,
  readOperatorBusActionError
} from '../../components/operator-bus-errors';
import {
  eligibleActiveBusTypes,
  eligiblePublishedSeatLayouts
} from '../../components/operator-bus-references';
import {
  CreateOperatorBusRequest,
  OperatorBusType,
  OperatorSeatLayout
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-bus-create-page',
  imports: [ReactiveFormsModule, RouterLink, EmptyStateComponent, OperatorRetryButtonComponent],
  templateUrl: './operator-bus-create.page.html'
})
export class OperatorBusCreatePageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly router = inject(Router);

  readonly form = new FormGroup({
    registrationNumber: new FormControl('', {
      nonNullable: true,
      validators: [
        Validators.required,
        Validators.minLength(4),
        Validators.maxLength(30),
        Validators.pattern(/^[A-Za-z0-9 -]{4,30}$/)
      ]
    }),
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
  busTypes: OperatorBusType[] = [];
  seatLayouts: OperatorSeatLayout[] = [];
  private loadVersion = 0;

  ngOnInit(): void {
    this.loadReferenceData();
  }

  loadReferenceData(): void {
    const operatorId = this.context.selectedOperatorId();
    const version = ++this.loadVersion;
    this.busTypes = [];
    this.seatLayouts = [];
    this.actionError = null;

    if (!operatorId || !this.context.canManageOperator()) {
      this.showWriteAccessDenied();
      return;
    }

    this.loading = true;
    this.pageError = null;
    forkJoin({
      busTypes: this.api.listActiveBusTypes(operatorId),
      seatLayouts: this.api.listPublishedSeatLayouts(operatorId)
    }).subscribe({
      next: ({ busTypes, seatLayouts }) => {
        if (version !== this.loadVersion) {
          return;
        }
        this.busTypes = eligibleActiveBusTypes(busTypes);
        this.seatLayouts = eligiblePublishedSeatLayouts(seatLayouts, operatorId);
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
    const registrationNumber = this.form.controls.registrationNumber.value.trim();
    this.form.controls.registrationNumber.setValue(registrationNumber);
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
    const displayName = value.displayName.trim();
    const request: CreateOperatorBusRequest = {
      busTypeId: value.busTypeId,
      seatLayoutId: value.seatLayoutId,
      registrationNumber,
      ...(displayName ? { displayName } : {})
    };

    this.submitting = true;
    this.api.createBus(operatorId, request).subscribe({
      next: (bus) => {
        this.submitting = false;
        void this.router.navigate(['/operator', operatorId, 'buses', bus.id], {
          queryParams: { created: 'true' }
        });
      },
      error: (error: unknown) => {
        this.submitting = false;
        const actionError = readOperatorBusActionError(error);
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
      message: 'Only an operator admin can create buses.'
    };
  }
}
