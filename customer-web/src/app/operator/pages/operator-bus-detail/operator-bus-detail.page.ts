import { Component, HostListener, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable, forkJoin } from 'rxjs';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import {
  OperatorBusActionError,
  readOperatorBusActionError
} from '../../components/operator-bus-errors';
import {
  eligibleActiveBusTypes,
  eligiblePublishedSeatLayouts
} from '../../components/operator-bus-references';
import { operatorStatusTone } from '../../components/operator-status';
import {
  OperatorBus,
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
  selector: 'app-operator-bus-detail-page',
  imports: [RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './operator-bus-detail.page.html'
})
export class OperatorBusDetailPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private loadVersion = 0;

  loading = true;
  error: OperatorPageError | null = null;
  bus: OperatorBus | null = null;
  busTypes: OperatorBusType[] = [];
  seatLayouts: OperatorSeatLayout[] = [];
  actionError: OperatorBusActionError | null = null;
  successMessage: string | null = null;
  pendingLifecycleAction: BusLifecycleAction | null = null;
  lifecycleSubmitting = false;
  readonly statusTone = operatorStatusTone;

  ngOnInit(): void {
    if (this.route.snapshot.queryParamMap.get('created') === 'true') {
      this.successMessage = 'Bus created successfully.';
    } else if (this.route.snapshot.queryParamMap.get('updated') === 'true') {
      this.successMessage = 'Bus details updated successfully.';
    }
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
    this.pendingLifecycleAction = null;
    if (!operatorId || !busId) {
      this.loading = false;
      this.error = {
        kind: 'not-found',
        title: 'Bus not found',
        message: 'The requested bus could not be identified.'
      };
      return;
    }

    this.loading = true;
    this.error = null;
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

  busTypeLabel(busTypeId: string): string {
    const busType = this.busTypes.find((candidate) => candidate.id === busTypeId);
    return busType ? `${busType.displayName} (${busType.code})` : busTypeId;
  }

  seatLayoutLabel(seatLayoutId: string): string {
    const layout = this.seatLayouts.find((candidate) => candidate.id === seatLayoutId);
    return layout ? `${layout.name} · version ${layout.version}` : seatLayoutId;
  }

  requestLifecycle(action: BusLifecycleAction): void {
    if (!this.context.canManageOperator() || this.lifecycleSubmitting) {
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
    const busId = this.route.snapshot.paramMap.get('busId');
    if (
      !action ||
      !operatorId ||
      !busId ||
      !this.bus ||
      this.bus.operatorId !== operatorId ||
      !this.context.canManageOperator()
    ) {
      this.pendingLifecycleAction = null;
      return;
    }

    let request: Observable<OperatorBus>;
    switch (action) {
      case 'activate':
        request = this.api.activateBus(operatorId, busId);
        break;
      case 'deactivate':
        request = this.api.deactivateBus(operatorId, busId);
        break;
      case 'maintenance':
        request = this.api.markBusMaintenance(operatorId, busId);
        break;
    }

    this.lifecycleSubmitting = true;
    this.actionError = null;
    request.subscribe({
      next: (bus) => {
        this.bus = bus;
        this.lifecycleSubmitting = false;
        this.pendingLifecycleAction = null;
        this.successMessage = this.lifecycleSuccessMessage(action);
      },
      error: (error: unknown) => {
        this.lifecycleSubmitting = false;
        const actionError = readOperatorBusActionError(error);
        if (actionError) {
          this.actionError = actionError;
          this.pendingLifecycleAction = null;
          return;
        }
        this.bus = null;
        this.pendingLifecycleAction = null;
        this.error = this.errors.handle(error);
      }
    });
  }

  lifecycleActionLabel(action: BusLifecycleAction): string {
    switch (action) {
      case 'activate':
        return 'Activate bus';
      case 'deactivate':
        return 'Deactivate bus';
      case 'maintenance':
        return 'Mark as maintenance';
    }
  }

  lifecycleConfirmationMessage(action: BusLifecycleAction): string {
    switch (action) {
      case 'activate':
        return 'Activate this bus so it can be used for new trips?';
      case 'deactivate':
        return 'Deactivate this bus? Existing trips will not be changed.';
      case 'maintenance':
        return 'Mark this bus as under maintenance? Existing trips will not be changed.';
    }
  }

  @HostListener('document:keydown.escape')
  closeConfirmationOnEscape(): void {
    this.cancelLifecycle();
  }

  private lifecycleSuccessMessage(action: BusLifecycleAction): string {
    switch (action) {
      case 'activate':
        return 'Bus activated successfully.';
      case 'deactivate':
        return 'Bus deactivated successfully.';
      case 'maintenance':
        return 'Bus marked as under maintenance.';
    }
  }
}

export type BusLifecycleAction = 'activate' | 'deactivate' | 'maintenance';
