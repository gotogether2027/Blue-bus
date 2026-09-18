import { Component, Input } from '@angular/core';
import { RouterLink } from '@angular/router';

export type OperatorTripOpsArea = 'detail' | 'bookings' | 'manifest' | 'boarding' | 'inventory';

@Component({
  selector: 'app-operator-trip-ops-nav',
  imports: [RouterLink],
  template: `
    <nav class="operator-page-actions" aria-label="Trip operations">
      <a
        class="btn"
        [class.btn-primary]="active === 'detail'"
        [routerLink]="['/operator', operatorId, 'trips', tripId]"
      >
        Trip detail
      </a>
      <a
        class="btn"
        [class.btn-primary]="active === 'bookings'"
        [routerLink]="['/operator', operatorId, 'trips', tripId, 'bookings']"
      >
        Bookings
      </a>
      <a
        class="btn"
        [class.btn-primary]="active === 'manifest'"
        [routerLink]="['/operator', operatorId, 'trips', tripId, 'bookings']"
        [queryParams]="{ view: 'manifest' }"
      >
        Passenger manifest
      </a>
      <a
        class="btn"
        [class.btn-primary]="active === 'boarding'"
        [routerLink]="['/operator', operatorId, 'trips', tripId, 'bookings']"
        [queryParams]="{ view: 'boarding' }"
      >
        Boarding
      </a>
      <a
        class="btn"
        [class.btn-primary]="active === 'inventory'"
        [routerLink]="['/operator', operatorId, 'trips', tripId, 'inventory']"
      >
        Inventory
      </a>
    </nav>
  `
})
export class OperatorTripOpsNavComponent {
  @Input({ required: true }) operatorId = '';
  @Input({ required: true }) tripId = '';
  @Input() active: OperatorTripOpsArea = 'bookings';
}
