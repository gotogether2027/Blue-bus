import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TripSearchService } from '../../core/api/trip-search.service';
import { TripSearchResult } from '../../core/api/models';
import { readApiError } from '../../core/api/api-error';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { durationLabel, formatInstant, formatMoney, locationLabel } from '../../shared/format';

type ResultSort = 'departure' | 'fare' | 'seats';

@Component({
  selector: 'app-search-page',
  imports: [FormsModule, RouterLink, EmptyStateComponent],
  templateUrl: './search.page.html'
})
export class SearchPageComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly trips = inject(TripSearchService);

  loading = true;
  error = '';
  results: TripSearchResult[] = [];
  originLocationId = '';
  destinationLocationId = '';
  serviceDate = '';
  sort: ResultSort = 'departure';

  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;
  readonly durationLabel = durationLabel;
  readonly locationLabel = locationLabel;

  get displayedResults(): TripSearchResult[] {
    const rows = [...this.results];
    switch (this.sort) {
      case 'fare':
        return rows.sort((a, b) => a.baseFare - b.baseFare);
      case 'seats':
        return rows.sort((a, b) => b.availableSeatCount - a.availableSeatCount);
      default:
        return rows.sort((a, b) => a.scheduledDepartureAt.localeCompare(b.scheduledDepartureAt));
    }
  }

  ngOnInit(): void {
    this.route.queryParamMap.subscribe((params) => {
      this.originLocationId = params.get('originLocationId') ?? '';
      this.destinationLocationId = params.get('destinationLocationId') ?? '';
      this.serviceDate = params.get('serviceDate') ?? '';
      this.load();
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

