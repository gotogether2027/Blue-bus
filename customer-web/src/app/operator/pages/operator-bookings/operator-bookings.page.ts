import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EMPTY, forkJoin, of, switchMap } from 'rxjs';
import { BookingStatus } from '../../../core/api/models';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { formatDate, formatInstant, formatMoney } from '../../../shared/format';
import {
  BOOKING_STATUSES,
  OperatorPassengerManifestRow,
  bookingBelongsToOperatorTrip,
  passengerManifestRows
} from '../../components/operator-booking-references';
import { operatorStatusTone } from '../../components/operator-status';
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
  imports: [FormsModule, RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './operator-bookings.page.html'
})
export class OperatorBookingsPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private loadVersion = 0;

  loading = true;
  error: OperatorPageError | null = null;
  tripId = '';
  trip: OperatorTrip | null = null;
  bus: OperatorBus | null = null;
  routeDetail: OperatorRoute | null = null;
  bookings: OperatorBooking[] = [];
  searchQuery = '';
  statusFilter: BookingStatus | 'ALL' = 'ALL';
  view: 'bookings' | 'manifest' = 'bookings';
  readonly bookingStatuses = BOOKING_STATUSES;
  readonly formatDate = formatDate;
  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;
  readonly statusTone = operatorStatusTone;

  get selectedOperatorId(): string {
    return this.context.selectedOperatorId() ?? '';
  }

  get passengerCount(): number {
    return this.bookings.reduce((count, booking) => count + booking.passengers.length, 0);
  }

  get filteredBookings(): OperatorBooking[] {
    return this.bookings.filter(
      (booking) => this.matchesStatus(booking.status) && this.matchesQuery(this.bookingSearchText(booking))
    );
  }

  get manifestRows(): OperatorPassengerManifestRow[] {
    return passengerManifestRows(this.bookings);
  }

  get filteredManifest(): OperatorPassengerManifestRow[] {
    return this.manifestRows.filter(
      (row) => this.matchesStatus(row.bookingStatus) && this.matchesQuery(this.manifestSearchText(row))
    );
  }

  ngOnInit(): void {
    this.tripId = this.route.snapshot.paramMap.get('tripId') ?? '';
    this.load();
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
    if (!operatorId || !tripId) {
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

  busLabel(): string {
    return busSummary(this.bus ?? undefined, this.trip?.busId ?? '');
  }

  routeLabel(): string {
    return routeSummary(this.routeDetail ?? undefined, this.trip?.routeId ?? '');
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
      booking.status,
      booking.trip.origin.city,
      booking.trip.destination.city,
      booking.trip.routeCode,
      booking.trip.routeName,
      ...booking.passengers.map((passenger) => passenger.fullName),
      ...booking.items.map((item) => item.seatNumber)
    ]
      .join(' ')
      .toLocaleLowerCase();
  }

  private manifestSearchText(row: OperatorPassengerManifestRow): string {
    return [
      row.bookingReference,
      row.bookingStatus,
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
