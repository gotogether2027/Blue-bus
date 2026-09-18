import { Component, HostListener, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EMPTY, Observable, forkJoin, of, switchMap } from 'rxjs';
import { TripSeatInventoryStatus } from '../../../core/api/models';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import {
  OperatorInventoryActionError,
  readOperatorInventoryActionError
} from '../../components/operator-inventory-errors';
import {
  InventorySeatDeck,
  groupTripInventory
} from '../../components/operator-inventory-references';
import { operatorStatusTone } from '../../components/operator-status';
import {
  busSummary,
  canMutateTripInventory,
  routeSummary
} from '../../components/operator-trip-references';
import {
  OperatorBus,
  OperatorRoute,
  OperatorTrip,
  OperatorTripSeatInventory
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-trip-inventory-page',
  imports: [FormsModule, RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './operator-trip-inventory.page.html'
})
export class OperatorTripInventoryPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private loadVersion = 0;

  loading = true;
  error: OperatorPageError | null = null;
  trip: OperatorTrip | null = null;
  bus: OperatorBus | null = null;
  routeDetail: OperatorRoute | null = null;
  inventory: OperatorTripSeatInventory[] = [];
  searchQuery = '';
  statusFilter: TripSeatInventoryStatus | 'ALL' = 'ALL';
  actionError: OperatorInventoryActionError | null = null;
  successMessage: string | null = null;
  pendingAction: InventorySeatAction | null = null;
  pendingSeat: OperatorTripSeatInventory | null = null;
  blockReason = '';
  reasonTouched = false;
  submitting = false;
  readonly statusTone = operatorStatusTone;
  readonly canMutateTripInventory = canMutateTripInventory;
  readonly physicalStatuses: TripSeatInventoryStatus[] = ['AVAILABLE', 'BLOCKED'];

  get selectedOperatorId(): string {
    return this.context.selectedOperatorId() ?? '';
  }

  get filteredInventory(): OperatorTripSeatInventory[] {
    const query = this.searchQuery.trim().toLocaleLowerCase();
    return this.inventory.filter((seat) => {
      if (this.statusFilter !== 'ALL' && seat.physicalStatus !== this.statusFilter) {
        return false;
      }
      if (!query) {
        return true;
      }
      const searchText = [
        seat.seatNumber,
        seat.seatType,
        seat.physicalStatus,
        seat.blockReason ?? '',
        String(seat.deckNumber),
        String(seat.rowNumber),
        String(seat.columnNumber)
      ]
        .join(' ')
        .toLocaleLowerCase();
      return searchText.includes(query);
    });
  }

  get groupedInventory(): InventorySeatDeck[] {
    return groupTripInventory(this.filteredInventory);
  }

  get availableCount(): number {
    return this.inventory.filter((seat) => seat.physicalStatus === 'AVAILABLE').length;
  }

  get blockedCount(): number {
    return this.inventory.filter((seat) => seat.physicalStatus === 'BLOCKED').length;
  }

  get reasonInvalid(): boolean {
    return this.reasonTouched && this.normalizedReason().length === 0;
  }

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
    this.inventory = [];
    this.actionError = null;
    this.closeDialog();
    if (!operatorId || !tripId) {
      this.loading = false;
      this.error = {
        kind: 'not-found',
        title: 'Trip not found',
        message: 'The requested trip inventory could not be identified.'
      };
      return;
    }

    this.loading = true;
    this.error = null;
    forkJoin({
      trip: this.api.getTrip(operatorId, tripId),
      inventory: this.api.listTripInventory(operatorId, tripId)
    })
      .pipe(
        switchMap(({ trip, inventory }) => {
          if (version !== this.loadVersion) {
            return EMPTY;
          }
          return forkJoin({
            trip: of(trip),
            inventory: of(inventory),
            bus: this.api.getBus(operatorId, trip.busId),
            route: this.api.getRoute(operatorId, trip.routeId)
          });
        })
      )
      .subscribe({
        next: ({ trip, inventory, bus, route }) => {
          if (version !== this.loadVersion) {
            return;
          }
          if (trip.operatorId !== operatorId || inventory.some((seat) => seat.tripId !== trip.id)) {
            this.loading = false;
            this.error = {
              kind: 'not-found',
              title: 'Trip not found',
              message: 'This trip inventory does not belong to the selected operator.'
            };
            return;
          }
          this.trip = trip;
          this.inventory = inventory;
          this.bus = bus;
          this.routeDetail = route;
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

  busLabel(): string {
    return busSummary(this.bus ?? undefined, this.trip?.busId ?? '');
  }

  routeLabel(): string {
    return routeSummary(this.routeDetail ?? undefined, this.trip?.routeId ?? '');
  }

  canMutate(): boolean {
    return (
      this.context.canManageOperator() &&
      !!this.trip &&
      canMutateTripInventory(this.trip.status)
    );
  }

  requestBlock(seat: OperatorTripSeatInventory): void {
    if (!this.canMutate() || this.submitting) {
      return;
    }
    this.actionError = null;
    this.pendingAction = 'block';
    this.pendingSeat = seat;
    this.blockReason = '';
    this.reasonTouched = false;
  }

  requestUnblock(seat: OperatorTripSeatInventory): void {
    if (!this.canMutate() || this.submitting) {
      return;
    }
    this.actionError = null;
    this.pendingAction = 'unblock';
    this.pendingSeat = seat;
    this.blockReason = '';
    this.reasonTouched = false;
  }

  cancelPending(): void {
    if (!this.submitting) {
      this.closeDialog();
    }
  }

  confirmPending(): void {
    const action = this.pendingAction;
    const seat = this.pendingSeat;
    const operatorId = this.context.selectedOperatorId();
    const tripId = this.route.snapshot.paramMap.get('tripId');
    if (
      !action ||
      !seat ||
      !operatorId ||
      !tripId ||
      !this.trip ||
      this.trip.operatorId !== operatorId ||
      !this.canMutate()
    ) {
      this.closeDialog();
      return;
    }

    if (action === 'block') {
      this.reasonTouched = true;
      const reason = this.normalizedReason();
      if (!reason) {
        return;
      }
      this.submitMutation(this.api.blockTripSeat(operatorId, tripId, seat.id, { reason }));
      return;
    }

    this.submitMutation(this.api.unblockTripSeat(operatorId, tripId, seat.id));
  }

  @HostListener('document:keydown.escape')
  closeConfirmationOnEscape(): void {
    this.cancelPending();
  }

  private submitMutation(request: Observable<OperatorTripSeatInventory>): void {
    this.submitting = true;
    this.actionError = null;
    request.subscribe({
      next: (updated) => {
        this.inventory = this.inventory.map((seat) => (seat.id === updated.id ? updated : seat));
        this.submitting = false;
        this.successMessage =
          updated.physicalStatus === 'BLOCKED'
            ? `Seat ${updated.seatNumber} is physically blocked.`
            : `Seat ${updated.seatNumber} is physically available again.`;
        this.closeDialog();
      },
      error: (error: unknown) => {
        this.submitting = false;
        const actionError = readOperatorInventoryActionError(error);
        if (actionError) {
          this.actionError = actionError;
          this.closeDialog();
          return;
        }
        this.trip = null;
        this.inventory = [];
        this.closeDialog();
        this.error = this.errors.handle(error);
      }
    });
  }

  private normalizedReason(): string {
    return this.blockReason.trim();
  }

  private closeDialog(): void {
    this.pendingAction = null;
    this.pendingSeat = null;
    this.blockReason = '';
    this.reasonTouched = false;
  }
}

export type InventorySeatAction = 'block' | 'unblock';
