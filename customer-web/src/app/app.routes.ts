import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/auth.guard';
import { AppShellComponent } from './core/layout/app-shell.component';
import { HomePageComponent } from './features/home/home.page';
import { SearchPageComponent } from './features/search/search.page';
import { LoginPageComponent } from './features/auth/login.page';
import { RegisterPageComponent } from './features/auth/register.page';
import { BookingsPageComponent } from './features/bookings/bookings.page';
import { BookingDetailPageComponent } from './features/bookings/booking-detail.page';
import { ProfilePageComponent } from './features/profile/profile.page';
import { SeatPlaceholderPageComponent } from './features/search/seat-placeholder.page';
import { CheckoutPlaceholderPageComponent } from './features/search/checkout-placeholder.page';

export const routes: Routes = [
  {
    path: '',
    component: AppShellComponent,
    children: [
      { path: '', component: HomePageComponent },
      { path: 'search', component: SearchPageComponent },
      { path: 'login', component: LoginPageComponent, canActivate: [guestGuard] },
      { path: 'register', component: RegisterPageComponent, canActivate: [guestGuard] },
      { path: 'bookings', component: BookingsPageComponent, canActivate: [authGuard] },
      { path: 'bookings/:bookingId', component: BookingDetailPageComponent, canActivate: [authGuard] },
      { path: 'profile', component: ProfilePageComponent, canActivate: [authGuard] },
      { path: 'trips/:tripId/seats', component: SeatPlaceholderPageComponent },
      { path: 'checkout', component: CheckoutPlaceholderPageComponent }
    ]
  },
  { path: '**', redirectTo: '' }
];
