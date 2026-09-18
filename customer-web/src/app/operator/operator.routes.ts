import { Routes } from '@angular/router';
import { operatorAdminGuard, operatorMembershipGuard } from './operator.guard';
import { OperatorAccessPageComponent } from './pages/operator-access/operator-access.page';
import { OperatorBookingDetailPageComponent } from './pages/operator-booking-detail/operator-booking-detail.page';
import { OperatorBookingsPageComponent } from './pages/operator-bookings/operator-bookings.page';
import { OperatorBusCreatePageComponent } from './pages/operator-bus-create/operator-bus-create.page';
import { OperatorBusDetailPageComponent } from './pages/operator-bus-detail/operator-bus-detail.page';
import { OperatorBusEditPageComponent } from './pages/operator-bus-edit/operator-bus-edit.page';
import { OperatorBusesPageComponent } from './pages/operator-buses/operator-buses.page';
import { OperatorDashboardPageComponent } from './pages/operator-dashboard/operator-dashboard.page';
import { OperatorShellComponent } from './pages/operator-shell/operator-shell.page';
import { OperatorTripDetailPageComponent } from './pages/operator-trip-detail/operator-trip-detail.page';
import { OperatorTripsPageComponent } from './pages/operator-trips/operator-trips.page';

export const OPERATOR_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    component: OperatorAccessPageComponent,
    title: 'Operator access · BLUE BUS'
  },
  {
    path: ':operatorId',
    component: OperatorShellComponent,
    canActivate: [operatorMembershipGuard],
    runGuardsAndResolvers: 'paramsChange',
    children: [
      {
        path: '',
        pathMatch: 'full',
        component: OperatorDashboardPageComponent,
        title: 'Operator dashboard · BLUE BUS'
      },
      {
        path: 'buses',
        pathMatch: 'full',
        component: OperatorBusesPageComponent,
        title: 'Operator buses · BLUE BUS'
      },
      {
        path: 'buses/new',
        component: OperatorBusCreatePageComponent,
        canActivate: [operatorAdminGuard],
        title: 'Create bus · BLUE BUS'
      },
      {
        path: 'buses/:busId/edit',
        component: OperatorBusEditPageComponent,
        canActivate: [operatorAdminGuard],
        title: 'Edit bus · BLUE BUS'
      },
      {
        path: 'buses/:busId',
        component: OperatorBusDetailPageComponent,
        title: 'Bus detail · BLUE BUS'
      },
      {
        path: 'trips',
        pathMatch: 'full',
        component: OperatorTripsPageComponent,
        title: 'Operator trips · BLUE BUS'
      },
      {
        path: 'trips/:tripId/bookings/:bookingId',
        component: OperatorBookingDetailPageComponent,
        title: 'Operator booking detail · BLUE BUS'
      },
      {
        path: 'trips/:tripId/bookings',
        component: OperatorBookingsPageComponent,
        title: 'Trip bookings · BLUE BUS'
      },
      {
        path: 'trips/:tripId',
        component: OperatorTripDetailPageComponent,
        title: 'Trip detail · BLUE BUS'
      }
    ]
  }
];
