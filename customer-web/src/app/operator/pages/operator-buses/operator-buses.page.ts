import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { OperatorRetryButtonComponent } from '../../components/operator-retry-button';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import {
  eligibleActiveBusTypes,
  eligiblePublishedSeatLayouts
} from '../../components/operator-bus-references';
import { operatorStatusTone } from '../../components/operator-status';
import {
  OperatorBus,
  OperatorBusStatus,
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
  selector: 'app-operator-buses-page',
  imports: [FormsModule, RouterLink, EmptyStateComponent, OperatorRetryButtonComponent, StatusBadgeComponent],
  templateUrl: './operator-buses.page.html'
})
export class OperatorBusesPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private loadVersion = 0;

  loading = true;
  error: OperatorPageError | null = null;
  buses: OperatorBus[] = [];
  busTypes: OperatorBusType[] = [];
  seatLayouts: OperatorSeatLayout[] = [];
  searchQuery = '';
  statusFilter: OperatorBusStatus | 'ALL' = 'ALL';
  readonly statusOptions: OperatorBusStatus[] = ['ACTIVE', 'MAINTENANCE', 'INACTIVE'];
  readonly statusTone = operatorStatusTone;
  readonly writeAccessDenied =
    this.route.snapshot.queryParamMap.get('writeAccessDenied') === 'true';

  get selectedOperatorId(): string {
    return this.context.selectedOperatorId() ?? '';
  }

  get filteredBuses(): OperatorBus[] {
    const query = this.searchQuery.trim().toLocaleLowerCase();
    return this.buses.filter((bus) => {
      if (this.statusFilter !== 'ALL' && bus.status !== this.statusFilter) {
        return false;
      }
      if (!query) {
        return true;
      }
      const searchText = [
        bus.registrationNumber,
        bus.displayName ?? '',
        this.busTypeLabel(bus.busTypeId),
        this.seatLayoutLabel(bus.seatLayoutId)
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
    this.buses = [];
    this.busTypes = [];
    this.seatLayouts = [];
    if (!operatorId) {
      this.showAccessDenied();
      return;
    }
    this.loading = true;
    this.error = null;
    forkJoin({
      buses: this.api.listBuses(operatorId),
      busTypes: this.api.listActiveBusTypes(operatorId),
      seatLayouts: this.api.listPublishedSeatLayouts(operatorId)
    }).subscribe({
      next: ({ buses, busTypes, seatLayouts }) => {
        if (version !== this.loadVersion) {
          return;
        }
        this.buses = buses;
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

  private showAccessDenied(): void {
    this.loading = false;
    this.error = {
      kind: 'forbidden',
      title: 'Operator access denied',
      message: "You don't have access to this operator."
    };
  }
}
