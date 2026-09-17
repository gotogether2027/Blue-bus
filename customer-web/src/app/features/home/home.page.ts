import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CustomerLocation } from '../../core/api/models';
import { LocationPickerComponent } from '../../shared/location-picker.component';
import { todayIsoDate } from '../../shared/format';

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
    void this.router.navigate(['/search'], {
      queryParams: {
        originLocationId: this.origin.id,
        destinationLocationId: this.destination.id,
        serviceDate: this.serviceDate
      }
    });
  }
}
