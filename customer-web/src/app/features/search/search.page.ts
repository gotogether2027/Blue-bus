import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TripSearchService } from '../../core/api/trip-search.service';
import { TripSearchResult } from '../../core/api/models';
import { readApiError } from '../../core/api/api-error';
import { CheckoutSessionService } from '../../core/checkout/checkout-session.service';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { durationLabel, formatClock, formatDate, formatInstant, formatMoney, locationLabel } from '../../shared/format';

type ResultSort = 'departure' | 'arrival' | 'fare' | 'fareDesc' | 'seats';
type DepartureBand = 'ANY' | 'MORNING' | 'AFTERNOON' | 'EVENING' | 'NIGHT';

@Component({
  selector: 'app-search-page',
  imports: [FormsModule, RouterLink, EmptyStateComponent],
  templateUrl: './search.page.html'
})
export class SearchPageComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly trips = inject(TripSearchService);
  private readonly checkout = inject(CheckoutSessionService);

  loading = true;
  error = '';
  results: TripSearchResult[] = [];
  originLocationId = '';
  destinationLocationId = '';
  serviceDate = '';
  sort: ResultSort = 'departure';
  operatorFilter = 'ALL';
  departureFilter: DepartureBand = 'ANY';

  readonly formatInstant = formatInstant;
  readonly formatClock = formatClock;
  readonly formatDate = formatDate;
  readonly formatMoney = formatMoney;
  readonly durationLabel = durationLabel;
  readonly locationLabel = locationLabel;

  get operators(): string[] {
    return [...new Set(this.results.map((trip) => trip.operatorName))].sort((left, right) =>
      left.localeCompare(right)
    );
  }

  get displayedResults(): TripSearchResult[] {
    const rows = this.results.filter((trip) => {
      if (this.operatorFilter !== 'ALL' && trip.operatorName !== this.operatorFilter) {
        return false;
      }
      return this.departureFilter === 'ANY' || departureBand(trip) === this.departureFilter;
    });
    switch (this.sort) {
      case 'fare':
        return rows.sort((a, b) => a.baseFare - b.baseFare);
      case 'fareDesc':
        return rows.sort((a, b) => b.baseFare - a.baseFare);
      case 'arrival':
        return rows.sort((a, b) => a.scheduledArrivalAt.localeCompare(b.scheduledArrivalAt));
      case 'seats':
        return rows.sort((a, b) => b.availableSeatCount - a.availableSeatCount);
      default:
        return rows.sort((a, b) => a.scheduledDepartureAt.localeCompare(b.scheduledDepartureAt));
    }
  }

  clearFilters(): void {
    this.operatorFilter = 'ALL';
    this.departureFilter = 'ANY';
  }

  ngOnInit(): void {
    this.route.queryParamMap.subscribe((params) => {
      this.originLocationId = params.get('originLocationId') ?? '';
      this.destinationLocationId = params.get('destinationLocationId') ?? '';
      this.serviceDate = params.get('serviceDate') ?? '';
      this.load();
    });
  }

  openSeats(trip: TripSearchResult): void {
    this.checkout.saveTripSnapshot(trip);
    void this.router.navigate(['/trips', trip.tripId, 'seats'], {
      queryParams: {
        originLocationId: this.originLocationId,
        destinationLocationId: this.destinationLocationId,
        serviceDate: this.serviceDate,
        originStopId: trip.origin.tripStopId,
        destinationStopId: trip.destination.tripStopId
      }
    });
  }

  private load(): void {
    this.error = '';
    this.results = [];
    if (!this.originLocationId || !this.destinationLocationId || !this.serviceDate) {
      this.loading = false;
      this.error = 'Start from Search and choose origin, destination, and date.';
      return;
    }
    this.loading = true;
    this.trips
      .search({
        originLocationId: this.originLocationId,
        destinationLocationId: this.destinationLocationId,
        serviceDate: this.serviceDate
      })
      .subscribe({
        next: (rows) => {
          this.results = rows;
          this.loading = false;
        },
        error: (err) => {
          this.loading = false;
          this.error = readApiError(err);
        }
      });
  }
}

function departureBand(trip: TripSearchResult): Exclude<DepartureBand, 'ANY'> {
  const hour = Number(
    new Intl.DateTimeFormat('en-GB', {
      timeZone: trip.timeZone || 'Asia/Kolkata',
      hour: '2-digit',
      hourCycle: 'h23'
    }).format(new Date(trip.scheduledDepartureAt))
  );
  if (hour >= 5 && hour < 12) {
    return 'MORNING';
  }
  if (hour >= 12 && hour < 17) {
    return 'AFTERNOON';
  }
  if (hour >= 17 && hour < 21) {
    return 'EVENING';
  }
  return 'NIGHT';
}

