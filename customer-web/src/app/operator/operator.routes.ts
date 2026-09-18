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
import { OperatorRouteCreatePageComponent } from './pages/operator-route-create/operator-route-create.page';
import { OperatorRouteDetailPageComponent } from './pages/operator-route-detail/operator-route-detail.page';
import { OperatorRouteEditPageComponent } from './pages/operator-route-edit/operator-route-edit.page';
import { OperatorRouteStopsPageComponent } from './pages/operator-route-stops/operator-route-stops.page';
import { OperatorRoutesPageComponent } from './pages/operator-routes/operator-routes.page';
import { OperatorShellComponent } from './pages/operator-shell/operator-shell.page';
import { OperatorTripCreatePageComponent } from './pages/operator-trip-create/operator-trip-create.page';
import { OperatorTripDetailPageComponent } from './pages/operator-trip-detail/operator-trip-detail.page';
import { OperatorTripEditPageComponent } from './pages/operator-trip-edit/operator-trip-edit.page';
import { OperatorTripInventoryPageComponent } from './pages/operator-trip-inventory/operator-trip-inventory.page';
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
        path: 'routes',
        pathMatch: 'full',
        component: OperatorRoutesPageComponent,
        title: 'Operator routes · BLUE BUS'
      },
      {
        path: 'routes/new',
        component: OperatorRouteCreatePageComponent,
        canActivate: [operatorAdminGuard],
        title: 'Create route · BLUE BUS'
      },
      {
        path: 'routes/:routeId/edit',
        component: OperatorRouteEditPageComponent,
        canActivate: [operatorAdminGuard],
        title: 'Edit route · BLUE BUS'
      },
      {
        path: 'routes/:routeId/stops',
        component: OperatorRouteStopsPageComponent,
        canActivate: [operatorAdminGuard],
        title: 'Manage route stops · BLUE BUS'
      },
      {
        path: 'routes/:routeId',
        component: OperatorRouteDetailPageComponent,
        title: 'Route detail · BLUE BUS'
      },
      {
        path: 'trips',
        pathMatch: 'full',
        component: OperatorTripsPageComponent,
        title: 'Operator trips · BLUE BUS'
      },
      {
        path: 'trips/new',
        component: OperatorTripCreatePageComponent,
        canActivate: [operatorAdminGuard],
        title: 'Create trip · BLUE BUS'
      },
      {
        path: 'trips/:tripId/edit',
        component: OperatorTripEditPageComponent,
        canActivate: [operatorAdminGuard],
        title: 'Edit trip commercial terms · BLUE BUS'
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
        path: 'trips/:tripId/inventory',
        component: OperatorTripInventoryPageComponent,
        title: 'Trip inventory · BLUE BUS'
      },
      {
        path: 'trips/:tripId',
        component: OperatorTripDetailPageComponent,
        title: 'Trip detail · BLUE BUS'
      }
    ]
  }
];
