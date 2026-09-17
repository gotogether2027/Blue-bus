import { Component, EventEmitter, Input, OnChanges, OnInit, Output, SimpleChanges, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CustomerLocation } from '../core/api/models';
import { LocationsService } from '../core/api/locations.service';
import { locationLabel } from './format';

@Component({
  selector: 'app-location-picker',
  imports: [FormsModule],
  template: `
    <div class="field">
      <span>{{ label }}</span>
      <input
        type="text"
        [placeholder]="placeholder"
        [ngModel]="query"
        (ngModelChange)="onQuery($event)"
        (focus)="open = true"
        autocomplete="off"
      />
      @if (error) {
        <small class="field-error">{{ error }}</small>
      }
      @if (open) {
        <div class="picker-menu" role="listbox">
          @if (loading) {
            <p class="muted">Loading locations…</p>
          } @else if (loadError) {
            <p class="field-error">{{ loadError }}</p>
          } @else if (filtered.length === 0) {
            <p class="muted">No matching locations.</p>
          } @else {
            @for (location of filtered; track location.id) {
              <button type="button" class="picker-item" (click)="choose(location)">
                <strong>{{ location.city }}</strong>
                <span>{{ location.locality || location.state }}</span>
              </button>
            }
          }
        </div>
      }
    </div>
  `
})
export class LocationPickerComponent implements OnInit, OnChanges {
  private readonly locationsService = inject(LocationsService);

  @Input() label = 'City';
  @Input() placeholder = 'Search city';
  @Input() selected: CustomerLocation | null = null;
  @Input() excludeId: string | null = null;
  @Output() selectedChange = new EventEmitter<CustomerLocation | null>();

  query = '';
  open = false;
  loading = false;
  loadError = '';
  error = '';
  locations: CustomerLocation[] = [];
  filtered: CustomerLocation[] = [];

  ngOnInit(): void {
    this.ensureLoaded();
    if (this.selected) {
      this.query = locationLabel(this.selected);
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['selected'] && this.selected) {
      this.query = locationLabel(this.selected);
    }
  }

  onQuery(value: string): void {
    this.query = value;
    this.open = true;
    this.selectedChange.emit(null);
    this.ensureLoaded();
    this.applyFilter();
  }

  choose(location: CustomerLocation): void {
    this.selectedChange.emit(location);
    this.query = locationLabel(location);
    this.open = false;
    this.error = '';
  }

  private ensureLoaded(): void {
    if (this.locations.length > 0 || this.loading) {
      return;
    }
    this.loading = true;
    this.loadError = '';
    this.locationsService.list().subscribe({
      next: (rows) => {
        this.locations = rows;
        this.loading = false;
        this.applyFilter();
      },
      error: () => {
        this.loading = false;
        this.loadError = 'Locations could not be loaded.';
      }
    });
  }

  private applyFilter(): void {
    const term = this.query.trim().toLowerCase();
    this.filtered = this.locations
      .filter((location) => location.id !== this.excludeId)
      .filter((location) => {
        if (!term) {
          return true;
        }
        return [location.city, location.state, location.locality, location.countryCode]
          .filter(Boolean)
          .some((part) => part!.toLowerCase().includes(term));
      })
      .slice(0, 12);
  }
}
