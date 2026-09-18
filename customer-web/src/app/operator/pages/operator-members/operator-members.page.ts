import { Component, HostListener, OnInit, inject } from '@angular/core';
import {
  FormControl,
  FormGroup,
  FormsModule,
  ReactiveFormsModule,
  Validators
} from '@angular/forms';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import {
  OperatorMemberActionError,
  readOperatorMemberActionError
} from '../../components/operator-member-errors';
import { operatorRoleLabel, operatorStatusTone } from '../../components/operator-status';
import {
  CreateOperatorMemberRequest,
  OperatorMember,
  OperatorMemberStatus,
  OperatorRole,
  UpdateOperatorMemberRequest
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

const UUID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

@Component({
  selector: 'app-operator-members-page',
  imports: [FormsModule, ReactiveFormsModule, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './operator-members.page.html'
})
export class OperatorMembersPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private loadVersion = 0;

  loading = true;
  submitting = false;
  error: OperatorPageError | null = null;
  actionError: OperatorMemberActionError | null = null;
  successMessage: string | null = null;
  members: OperatorMember[] = [];
  searchQuery = '';
  statusFilter: OperatorMemberStatus | 'ALL' = 'ALL';
  roleFilter: OperatorRole | 'ALL' = 'ALL';
  adding = false;
  editingMember: OperatorMember | null = null;
  pendingDeactivate: OperatorMember | null = null;
  readonly statusOptions: OperatorMemberStatus[] = ['ACTIVE', 'INACTIVE'];
  readonly roleOptions: OperatorRole[] = ['OPERATOR_ADMIN', 'OPERATOR_STAFF'];
  readonly statusTone = operatorStatusTone;
  readonly roleLabel = operatorRoleLabel;
  readonly lastAdminNote =
    'BLUE BUS rejects a change that would leave this operator without an active operator admin. The server decision is authoritative.';

  readonly addForm = new FormGroup({
    userId: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(UUID_PATTERN)]
    }),
    role: new FormControl<OperatorRole>('OPERATOR_STAFF', {
      nonNullable: true,
      validators: [Validators.required]
    })
  });

  readonly editForm = new FormGroup({
    role: new FormControl<OperatorRole>('OPERATOR_STAFF', {
      nonNullable: true,
      validators: [Validators.required]
    }),
    status: new FormControl<OperatorMemberStatus>('ACTIVE', {
      nonNullable: true,
      validators: [Validators.required]
    })
  });

  get selectedOperatorId(): string {
    return this.context.selectedOperatorId() ?? '';
  }

  get filteredMembers(): OperatorMember[] {
    const query = this.searchQuery.trim().toLocaleLowerCase();
    return this.members.filter((member) => {
      if (this.statusFilter !== 'ALL' && member.status !== this.statusFilter) {
        return false;
      }
      if (this.roleFilter !== 'ALL' && member.role !== this.roleFilter) {
        return false;
      }
      if (!query) {
        return true;
      }
      const searchText = [
        this.memberName(member),
        member.email,
        member.userId,
        member.role,
        member.status,
        member.firstName ?? '',
        member.lastName ?? ''
      ]
        .join(' ')
        .toLocaleLowerCase();
      return searchText.includes(query);
    });
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const version = ++this.loadVersion;
    this.members = [];
    this.actionError = null;
    this.closePanels(false);
    if (!operatorId) {
      this.showAccessDenied();
      return;
    }
    this.loading = true;
    this.error = null;
    this.api.listMembers(operatorId).subscribe({
      next: (members) => {
        if (version !== this.loadVersion) {
          return;
        }
        this.members = members;
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

  memberName(member: OperatorMember): string {
    const name = [member.firstName, member.lastName]
      .map((part) => part?.trim() ?? '')
      .filter((part) => part.length > 0)
      .join(' ');
    return name || member.email;
  }

  startAdd(): void {
    if (!this.context.canManageOperator()) {
      return;
    }
    this.actionError = null;
    this.successMessage = null;
    this.editingMember = null;
    this.pendingDeactivate = null;
    this.addForm.reset({ userId: '', role: 'OPERATOR_STAFF' });
    this.adding = true;
  }

  cancelAdd(): void {
    if (!this.submitting) {
      this.adding = false;
      this.addForm.reset({ userId: '', role: 'OPERATOR_STAFF' });
    }
  }

  submitAdd(): void {
    this.actionError = null;
    const userId = this.addForm.controls.userId.value.trim();
    this.addForm.controls.userId.setValue(userId);
    this.addForm.markAllAsTouched();
    if (this.addForm.invalid || this.submitting) {
      return;
    }

    const operatorId = this.context.selectedOperatorId();
    if (!operatorId || !this.context.canManageOperator()) {
      this.showAccessDenied();
      return;
    }

    const request: CreateOperatorMemberRequest = {
      userId,
      role: this.addForm.controls.role.value
    };
    this.submitting = true;
    this.api.addMember(operatorId, request).subscribe({
      next: () => {
        this.submitting = false;
        this.adding = false;
        this.addForm.reset({ userId: '', role: 'OPERATOR_STAFF' });
        this.successMessage = 'Team member added.';
        this.load();
      },
      error: (error: unknown) => this.handleMutationError(error)
    });
  }

  startEdit(member: OperatorMember): void {
    if (!this.context.canManageOperator()) {
      return;
    }
    this.actionError = null;
    this.successMessage = null;
    this.adding = false;
    this.pendingDeactivate = null;
    this.editingMember = member;
    this.editForm.reset({ role: member.role, status: member.status });
  }

  cancelEdit(): void {
    if (!this.submitting) {
      this.editingMember = null;
    }
  }

  submitEdit(): void {
    this.actionError = null;
    this.editForm.markAllAsTouched();
    const member = this.editingMember;
    const operatorId = this.context.selectedOperatorId();
    if (
      this.editForm.invalid ||
      this.submitting ||
      !member ||
      !operatorId ||
      !this.context.canManageOperator()
    ) {
      return;
    }

    const value = this.editForm.getRawValue();
    const request: UpdateOperatorMemberRequest = {};
    if (value.role !== member.role) {
      request.role = value.role;
    }
    if (value.status !== member.status) {
      request.status = value.status;
    }
    if (!request.role && !request.status) {
      this.actionError = {
        title: 'No changes to save',
        message: 'Update the role or status before saving.'
      };
      return;
    }

    this.submitting = true;
    this.api.updateMember(operatorId, member.userId, request).subscribe({
      next: () => {
        this.submitting = false;
        this.editingMember = null;
        this.successMessage = 'Team member updated.';
        this.load();
      },
      error: (error: unknown) => this.handleMutationError(error)
    });
  }

  requestDeactivate(member: OperatorMember): void {
    if (!this.context.canManageOperator() || member.status === 'INACTIVE') {
      return;
    }
    this.actionError = null;
    this.successMessage = null;
    this.adding = false;
    this.editingMember = null;
    this.pendingDeactivate = member;
  }

  cancelDeactivate(): void {
    if (!this.submitting) {
      this.pendingDeactivate = null;
    }
  }

  confirmDeactivate(): void {
    const member = this.pendingDeactivate;
    const operatorId = this.context.selectedOperatorId();
    if (!member || !operatorId || !this.context.canManageOperator() || this.submitting) {
      return;
    }
    this.submitting = true;
    this.actionError = null;
    this.api.deactivateMember(operatorId, member.userId).subscribe({
      next: () => {
        this.submitting = false;
        this.pendingDeactivate = null;
        this.successMessage = 'Team member deactivated. Operator access for this user is removed.';
        this.load();
      },
      error: (error: unknown) => this.handleMutationError(error)
    });
  }

  @HostListener('document:keydown.escape')
  closePanelsOnEscape(): void {
    this.closePanels();
  }

  private closePanels(allowWhileSubmitting = true): void {
    if (this.submitting && allowWhileSubmitting) {
      return;
    }
    this.adding = false;
    this.editingMember = null;
    this.pendingDeactivate = null;
  }

  private handleMutationError(error: unknown): void {
    this.submitting = false;
    const actionError = readOperatorMemberActionError(error);
    if (actionError) {
      this.actionError = actionError;
      this.editingMember = null;
      this.pendingDeactivate = null;
      return;
    }
    this.members = [];
    this.closePanels(false);
    this.error = this.errors.handle(error);
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
