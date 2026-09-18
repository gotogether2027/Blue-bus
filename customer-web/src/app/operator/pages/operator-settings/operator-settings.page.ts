import { Component, OnInit, inject } from '@angular/core';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators
} from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { OperatorRetryButtonComponent } from '../../components/operator-retry-button';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import {
  OperatorProfileActionError,
  readOperatorProfileActionError
} from '../../components/operator-profile-errors';
import { operatorRoleLabel, operatorStatusTone } from '../../components/operator-status';
import {
  OperatorProfile,
  UpdateOperatorSupportContactRequest
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

const E164_PATTERN = /^\+[1-9]\d{1,14}$/;

@Component({
  selector: 'app-operator-settings-page',
  imports: [ReactiveFormsModule, RouterLink, EmptyStateComponent, OperatorRetryButtonComponent, StatusBadgeComponent],
  templateUrl: './operator-settings.page.html'
})
export class OperatorSettingsPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private loadVersion = 0;

  loading = true;
  submitting = false;
  error: OperatorPageError | null = null;
  actionError: OperatorProfileActionError | null = null;
  successMessage: string | null = null;
  operator: OperatorProfile | null = null;
  readonly statusTone = operatorStatusTone;
  readonly roleLabel = operatorRoleLabel;
  readonly form = new FormGroup({
    supportEmail: new FormControl('', {
      nonNullable: true,
      validators: [Validators.email, Validators.maxLength(320)]
    }),
    supportPhoneE164: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(20), Validators.pattern(E164_PATTERN)]
    })
  });

  get selectedOperatorId(): string {
    return this.context.selectedOperatorId() ?? '';
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const version = ++this.loadVersion;
    this.operator = null;
    this.actionError = null;
    this.submitting = false;
    this.form.enable({ emitEvent: false });
    this.form.reset({ supportEmail: '', supportPhoneE164: '' });
    if (!operatorId) {
      this.showAccessDenied();
      return;
    }

    this.loading = true;
    this.error = null;
    this.api.getOperator(operatorId).subscribe({
      next: (operator) => {
        if (version !== this.loadVersion) {
          return;
        }
        if (operator.id !== operatorId) {
          this.loading = false;
          this.error = {
            kind: 'not-found',
            title: 'Resource not found',
            message: 'The requested operator resource was not found.'
          };
          return;
        }
        this.applyProfile(operator);
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

  submit(): void {
    this.actionError = null;
    this.successMessage = null;
    this.form.controls.supportEmail.setValue(this.form.controls.supportEmail.value.trim());
    this.form.controls.supportPhoneE164.setValue(this.form.controls.supportPhoneE164.value.trim());
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting) {
      return;
    }

    const operatorId = this.context.selectedOperatorId();
    if (!operatorId || !this.operator || this.operator.id !== operatorId) {
      this.showAccessDenied();
      return;
    }
    if (!this.context.canManageOperator()) {
      this.error = {
        kind: 'forbidden',
        title: 'Operator admin access required',
        message: 'Only an operator admin can update support contact details.'
      };
      return;
    }

    const request = this.supportContactRequest();
    if (!this.hasSupportContactChanges(request)) {
      this.actionError = {
        title: 'No changes to save',
        message: 'Update the support email or phone before saving.'
      };
      return;
    }

    const version = this.loadVersion;
    this.submitting = true;
    this.form.disable({ emitEvent: false });
    this.api.updateSupportContact(operatorId, request).subscribe({
      next: (operator) => {
        if (version !== this.loadVersion || this.context.selectedOperatorId() !== operatorId) {
          return;
        }
        this.submitting = false;
        this.form.enable({ emitEvent: false });
        if (operator.id !== operatorId) {
          this.operator = null;
          this.error = {
            kind: 'not-found',
            title: 'Resource not found',
            message: 'The requested operator resource was not found.'
          };
          return;
        }
        this.applyProfile(operator);
        this.successMessage = 'Support contact updated.';
      },
      error: (error: unknown) => {
        if (version !== this.loadVersion) {
          return;
        }
        this.submitting = false;
        this.form.enable({ emitEvent: false });
        const actionError = readOperatorProfileActionError(error);
        if (actionError) {
          this.actionError = actionError;
          return;
        }
        this.operator = null;
        this.error = this.errors.handle(error);
      }
    });
  }

  private applyProfile(operator: OperatorProfile): void {
    this.operator = operator;
    this.form.reset({
      supportEmail: operator.supportEmail ?? '',
      supportPhoneE164: operator.supportPhoneE164 ?? ''
    });
  }

  private supportContactRequest(): UpdateOperatorSupportContactRequest {
    const supportEmail = this.form.controls.supportEmail.value.trim();
    const supportPhoneE164 = this.form.controls.supportPhoneE164.value.trim();
    return {
      supportEmail: supportEmail || null,
      supportPhoneE164: supportPhoneE164 || null
    };
  }

  private hasSupportContactChanges(request: UpdateOperatorSupportContactRequest): boolean {
    return (
      this.normalizeContact(request.supportEmail) !==
        this.normalizeContact(this.operator?.supportEmail) ||
      this.normalizeContact(request.supportPhoneE164) !==
        this.normalizeContact(this.operator?.supportPhoneE164)
    );
  }

  private normalizeContact(value: string | null | undefined): string {
    return value?.trim() ?? '';
  }

  private showAccessDenied(): void {
    this.loading = false;
    this.submitting = false;
    this.error = {
      kind: 'forbidden',
      title: 'Operator access denied',
      message: "You don't have access to this operator."
    };
  }
}
