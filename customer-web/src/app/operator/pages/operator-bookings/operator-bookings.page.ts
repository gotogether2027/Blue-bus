import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EMPTY, Subscription, forkJoin, of, switchMap } from 'rxjs';
import { BookingItemStatus, BookingStatus } from '../../../core/api/models';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { OperatorRetryButtonComponent } from '../../components/operator-retry-button';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { formatDate, formatInstant, formatMoney } from '../../../shared/format';
import {
  BOOKING_ITEM_STATUSES,
  BOOKING_STATUSES,
  OPERATOR_TICKET_UNAVAILABLE_NOTE,
  OperatorManifestGroup,
  OperatorManifestGroupBy,
  OperatorPassengerManifestRow,
  OperatorStopOption,
  bookingBelongsToOperatorTrip,
  bookingConfirmationLabel,
  bookingHasItemStatus,
  groupPassengerManifestRows,
  parseTripBookingView,
  passengerManifestRows,
  uniqueDestinationOptions,
  uniqueOriginOptions
} from '../../components/operator-booking-references';
import { operatorStatusTone } from '../../components/operator-status';
import { OperatorTripOpsNavComponent } from '../../components/operator-trip-ops-nav';
import { busSummary, routeSummary } from '../../components/operator-trip-references';
import {
  OperatorBooking,
  OperatorBus,
  OperatorRoute,
  OperatorTrip
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-bookings-page',
  imports: [FormsModule, RouterLink, EmptyStateComponent, OperatorRetryButtonComponent, StatusBadgeComponent, OperatorTripOpsNavComponent],
  templateUrl: './operator-bookings.page.html'
})
export class OperatorBookingsPageComponent implements OnInit, OnDestroy {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private loadVersion = 0;
  private viewSubscription: Subscription | null = null;

  loading = true;
  error: OperatorPageError | null = null;
  tripId = '';
  trip: OperatorTrip | null = null;
  bus: OperatorBus | null = null;
  routeDetail: OperatorRoute | null = null;
  bookings: OperatorBooking[] = [];
  searchQuery = '';
  statusFilter: BookingStatus | 'ALL' = 'ALL';
  itemStatusFilter: BookingItemStatus | 'ALL' = 'ALL';
  originFilter = 'ALL';
  destinationFilter = 'ALL';
  groupBy: OperatorManifestGroupBy = 'seat';
  view: OperatorTripBookingView = 'bookings';
  readonly bookingStatuses = BOOKING_STATUSES;
  readonly itemStatuses = BOOKING_ITEM_STATUSES;
  readonly ticketUnavailableNote = OPERATOR_TICKET_UNAVAILABLE_NOTE;
  readonly formatDate = formatDate;
  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;
  readonly statusTone = operatorStatusTone;
  readonly confirmationLabel = bookingConfirmationLabel;

  get selectedOperatorId(): string {
    return this.context.selectedOperatorId() ?? '';
  }

  get passengerCount(): number {
    return this.bookings.reduce((count, booking) => count + booking.passengers.length, 0);
  }

  get itemCount(): number {
    return this.bookings.reduce((count, booking) => count + booking.items.length, 0);
  }

  get confirmedBookingCount(): number {
    return this.bookings.filter((booking) => booking.status === 'CONFIRMED').length;
  }

  get originOptions(): OperatorStopOption[] {
    return uniqueOriginOptions(this.manifestRows);
  }

  get destinationOptions(): OperatorStopOption[] {
    return uniqueDestinationOptions(this.manifestRows);
  }

  get filteredBookings(): OperatorBooking[] {
    return this.bookings.filter(
      (booking) =>
        this.matchesStatus(booking.status) &&
        bookingHasItemStatus(booking, this.itemStatusFilter) &&
        this.matchesQuery(this.bookingSearchText(booking))
    );
  }

  get hasActiveFilters(): boolean {
    return (
      this.searchQuery.trim() !== '' ||
      this.statusFilter !== 'ALL' ||
      this.itemStatusFilter !== 'ALL' ||
      this.originFilter !== 'ALL' ||
      this.destinationFilter !== 'ALL'
    );
  }

  get manifestRows(): OperatorPassengerManifestRow[] {
    return passengerManifestRows(this.bookings);
  }

  get filteredManifest(): OperatorPassengerManifestRow[] {
    return this.manifestRows.filter((row) => this.matchesRow(row));
  }

  get groupedBoarding(): OperatorManifestGroup[] {
    return groupPassengerManifestRows(this.filteredManifest, this.groupBy);
  }

  ngOnInit(): void {
    this.tripId = this.route.snapshot.paramMap.get('tripId') ?? '';
    this.view = parseTripBookingView(this.route.snapshot.queryParamMap.get('view'));
    const queryParams = this.route.queryParamMap;
    if (queryParams && typeof queryParams.subscribe === 'function') {
      this.viewSubscription = queryParams.subscribe((params) => {
        this.view = parseTripBookingView(params.get('view'));
      });
    }
    this.load();
  }

  ngOnDestroy(): void {
    this.viewSubscription?.unsubscribe();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const tripId = this.route.snapshot.paramMap.get('tripId') ?? '';
    const version = ++this.loadVersion;
    this.tripId = tripId;
    this.trip = null;
    this.bus = null;
    this.routeDetail = null;
    this.bookings = [];
    this.searchQuery = '';
    this.statusFilter = 'ALL';
    this.itemStatusFilter = 'ALL';
    this.originFilter = 'ALL';
    this.destinationFilter = 'ALL';
    this.groupBy = 'seat';
    if (!operatorId) {
      this.showAccessDenied();
      return;
    }
    if (!tripId) {
      this.loading = false;
      this.error = {
        kind: 'not-found',
        title: 'Trip bookings not found',
        message: 'The requested trip could not be identified.'
      };
      return;
    }

    this.loading = true;
    this.error = null;
    forkJoin({
      trip: this.api.getTrip(operatorId, tripId),
      bookings: this.api.listTripBookings(operatorId, tripId)
    })
      .pipe(
        switchMap(({ trip, bookings }) => {
          if (version !== this.loadVersion) {
            return EMPTY;
          }
          return forkJoin({
            trip: of(trip),
            bookings: of(bookings),
            bus: this.api.getBus(operatorId, trip.busId),
            route: this.api.getRoute(operatorId, trip.routeId)
          });
        })
      )
      .subscribe({
        next: ({ trip, bookings, bus, route }) => {
          if (version !== this.loadVersion) {
            return;
          }
          if (
            trip.operatorId !== operatorId ||
            bookings.some((booking) => !bookingBelongsToOperatorTrip(booking, operatorId, trip.id))
          ) {
            this.loading = false;
            this.error = {
              kind: 'not-found',
              title: 'Trip bookings not found',
              message: 'These trip bookings do not belong to the selected operator.'
            };
            return;
          }
          this.trip = trip;
          this.bookings = bookings;
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

  showBookings(): void {
    this.view = 'bookings';
  }

  showManifest(): void {
    this.view = 'manifest';
  }

  showBoarding(): void {
    this.view = 'boarding';
  }

  clearFilters(): void {
    this.searchQuery = '';
    this.statusFilter = 'ALL';
    this.itemStatusFilter = 'ALL';
    this.originFilter = 'ALL';
    this.destinationFilter = 'ALL';
  }

  busLabel(): string {
    return busSummary(this.bus ?? undefined, this.trip?.busId ?? '');
  }

  routeLabel(): string {
    return routeSummary(this.routeDetail ?? undefined, this.trip?.routeId ?? '');
  }

  private matchesRow(row: OperatorPassengerManifestRow): boolean {
    if (!this.matchesStatus(row.bookingStatus)) {
      return false;
    }
    if (this.itemStatusFilter !== 'ALL' && row.itemStatus !== this.itemStatusFilter) {
      return false;
    }
    if (this.originFilter !== 'ALL' && this.originFilter !== `${row.originSequence}:${row.originLabel}`) {
      return false;
    }
    if (
      this.destinationFilter !== 'ALL' &&
      this.destinationFilter !== `${row.destinationSequence}:${row.destinationLabel}`
    ) {
      return false;
    }
    return this.matchesQuery(this.manifestSearchText(row));
  }

  private showAccessDenied(): void {
    this.loading = false;
    this.error = {
      kind: 'forbidden',
      title: 'Operator access denied',
      message: "You don't have access to this operator."
    };
  }

  private matchesStatus(status: BookingStatus): boolean {
    return this.statusFilter === 'ALL' || status === this.statusFilter;
  }

  private matchesQuery(searchText: string): boolean {
    const query = this.searchQuery.trim().toLocaleLowerCase();
    return !query || searchText.includes(query);
  }

  private bookingSearchText(booking: OperatorBooking): string {
    return [
      booking.bookingReference,
      ...booking.passengers.map((passenger) => passenger.fullName)
    ]
      .join(' ')
      .toLocaleLowerCase();
  }

  private manifestSearchText(row: OperatorPassengerManifestRow): string {
    return [
      row.bookingReference,
      row.bookingStatus,
      row.itemStatus,
      row.passengerName ?? '',
      row.seatNumber,
      row.originLabel,
      row.destinationLabel,
      row.gender ?? ''
    ]
      .join(' ')
      .toLocaleLowerCase();
  }
}

export type OperatorTripBookingView = 'bookings' | 'manifest' | 'boarding';
