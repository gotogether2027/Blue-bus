import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/auth.guard';
import { checkoutPassengersGuard, holdGuard } from './core/checkout/checkout.guard';
import { AppShellComponent } from './core/layout/app-shell.component';
import { HomePageComponent } from './features/home/home.page';
import { SearchPageComponent } from './features/search/search.page';
import { SeatPageComponent } from './features/search/seats.page';
import { LoginPageComponent } from './features/auth/login.page';
import { RegisterPageComponent } from './features/auth/register.page';
import { BookingsPageComponent } from './features/bookings/bookings.page';
import { BookingDetailPageComponent } from './features/bookings/booking-detail.page';
import { ConfirmationPageComponent } from './features/checkout/confirmation.page';
import { PassengersPageComponent } from './features/checkout/passengers.page';
import { PaymentPageComponent } from './features/checkout/payment.page';
import { ReviewPageComponent } from './features/checkout/review.page';
import { ProfilePageComponent } from './features/profile/profile.page';

export const routes: Routes = [
  {
    path: 'operator',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./operator/operator.routes').then((module) => module.OPERATOR_ROUTES)
  },
  {
    path: '',
    component: AppShellComponent,
    children: [
      { path: '', component: HomePageComponent },
      { path: 'search', component: SearchPageComponent },
      { path: 'login', component: LoginPageComponent, canActivate: [guestGuard] },
      { path: 'register', component: RegisterPageComponent, canActivate: [guestGuard] },
      { path: 'bookings', component: BookingsPageComponent, canActivate: [authGuard] },
      {
        path: 'bookings/:bookingId/confirmation',
        component: ConfirmationPageComponent,
        canActivate: [authGuard]
      },
      { path: 'bookings/:bookingId', component: BookingDetailPageComponent, canActivate: [authGuard] },
      { path: 'profile', component: ProfilePageComponent, canActivate: [authGuard] },
      { path: 'trips/:tripId/seats', component: SeatPageComponent },
      {
        path: 'checkout/:holdId/passengers',
        component: PassengersPageComponent,
        canActivate: [authGuard, holdGuard]
      },
      {
        path: 'checkout/:holdId/review',
        component: ReviewPageComponent,
        canActivate: [authGuard, holdGuard, checkoutPassengersGuard]
      },
      { path: 'payment/:bookingId', component: PaymentPageComponent, canActivate: [authGuard] }
    ]
  },
  { path: '**', redirectTo: '' }
];
