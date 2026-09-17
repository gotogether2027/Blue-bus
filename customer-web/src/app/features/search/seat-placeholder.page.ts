import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-seat-placeholder-page',
  imports: [RouterLink],
  template: `
    <section class="page">
      <h1>Seat selection</h1>
      <p class="muted">
        Seat maps and holds will use the existing availability and hold APIs in the next frontend phase.
        Payment checkout is not enabled in this foundation.
      </p>
      <a routerLink="/search" class="btn">Back to results</a>
    </section>
  `
})
export class SeatPlaceholderPageComponent {}
