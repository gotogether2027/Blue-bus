import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-checkout-placeholder-page',
  imports: [RouterLink],
  template: `
    <section class="page">
      <h1>Checkout</h1>
      <p class="muted">Passenger review and Razorpay checkout will be added after seat selection.</p>
      <a routerLink="/" class="btn">Home</a>
    </section>
  `
})
export class CheckoutPlaceholderPageComponent {}
