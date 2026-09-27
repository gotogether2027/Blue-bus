import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CustomerLocation } from '../../core/api/models';
import { LocationPickerComponent } from '../../shared/location-picker.component';
import { todayIsoDate } from '../../shared/format';

interface RecentSearch {
  origin: CustomerLocation;
  destination: CustomerLocation;
  serviceDate: string;
}

const RECENT_KEY = 'bb.recent-searches';

@Component({
  selector: 'app-home-page',
  imports: [FormsModule, LocationPickerComponent],
  templateUrl: './home.page.html'
})
export class HomePageComponent {
  private readonly router = inject(Router);
  origin: CustomerLocation | null = null;
  destination: CustomerLocation | null = null;
  serviceDate = todayIsoDate();
  minDate = todayIsoDate();
  error = '';
  recent: RecentSearch[] = this.readRecent();

  search(): void {
    this.error = '';
    if (!this.origin || !this.destination) {
      this.error = 'Choose origin and destination.';
      return;
    }
    if (this.origin.id === this.destination.id) {
      this.error = 'Origin and destination must be different.';
      return;
    }
    if (!this.serviceDate || this.serviceDate < this.minDate) {
      this.error = 'Choose a journey date that is today or later.';
      return;
    }
    this.remember();
    void this.router.navigate(['/search'], {
      queryParams: {
        originLocationId: this.origin.id,
        destinationLocationId: this.destination.id,
        serviceDate: this.serviceDate
      }
    });
  }

  swap(): void {
    const origin = this.origin;
    this.origin = this.destination;
    this.destination = origin;
  }

  useRecent(item: RecentSearch): void {
    this.origin = item.origin;
    this.destination = item.destination;
    this.serviceDate = item.serviceDate < this.minDate ? this.minDate : item.serviceDate;
    this.search();
  }

  private remember(): void {
    if (!this.origin || !this.destination) {
      return;
    }
    const entry: RecentSearch = {
      origin: this.origin,
      destination: this.destination,
      serviceDate: this.serviceDate
    };
    this.recent = [
      entry,
      ...this.recent.filter(
        (item) =>
          item.origin.id !== entry.origin.id ||
          item.destination.id !== entry.destination.id ||
          item.serviceDate !== entry.serviceDate
      )
    ].slice(0, 3);
    try {
      localStorage.setItem(RECENT_KEY, JSON.stringify(this.recent));
    } catch {
      this.recent = this.recent;
    }
  }

  private readRecent(): RecentSearch[] {
    try {
      const raw = localStorage.getItem(RECENT_KEY);
      if (!raw) {
        return [];
      }
      const parsed = JSON.parse(raw) as RecentSearch[];
      return Array.isArray(parsed)
        ? parsed.filter((item) => item?.origin?.id && item?.destination?.id && item?.serviceDate).slice(0, 3)
        : [];
    } catch {
      return [];
    }
  }
}
