import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { HoldsService } from '../api/holds.service';
import { newIdempotencyKey } from '../api/idempotency';
import { CheckoutSessionService } from './checkout-session.service';

export const holdGuard: CanActivateFn = (route) => {
  const holds = inject(HoldsService);
  const checkout = inject(CheckoutSessionService);
  const router = inject(Router);
  const holdId = route.paramMap.get('holdId');
  if (!holdId) {
    return router.createUrlTree(['/']);
  }
  return holds.get(holdId).pipe(
    map((hold) => {
      if (hold.status !== 'ACTIVE') {
        return router.createUrlTree(['/trips', hold.tripId, 'seats'], {
          queryParams: {
            originStopId: hold.originStopId,
            destinationStopId: hold.destinationStopId,
            holdStatus: hold.status
          }
        });
      }
      const session = checkout.read();
      if (!session || session.holdId !== hold.holdId) {
        checkout.write({
          holdId: hold.holdId,
          tripId: hold.tripId,
          originStopId: hold.originStopId,
          destinationStopId: hold.destinationStopId,
          expiresAt: hold.expiresAt,
          seats:
            session?.holdId === hold.holdId
              ? session.seats
              : hold.seatInventoryIds.map((inventoryId) => ({
                  inventoryId,
                  seatNumber: '',
                  seatType: ''
                })),
          passengers: session?.holdId === hold.holdId ? session.passengers : [],
          bookingIdempotencyKey:
            session?.holdId === hold.holdId && session.bookingIdempotencyKey
              ? session.bookingIdempotencyKey
              : newIdempotencyKey(),
          paymentIdempotencyKey: session?.holdId === hold.holdId ? session.paymentIdempotencyKey : null,
          bookingId: session?.holdId === hold.holdId ? session.bookingId : null,
          tripSnapshot: session?.holdId === hold.holdId ? session.tripSnapshot : checkout.tripSnapshot(hold.tripId)
        });
      }
      return true;
    }),
    catchError(() => of(router.createUrlTree(['/'])))
  );
};

export const checkoutPassengersGuard: CanActivateFn = (route) => {
  const checkout = inject(CheckoutSessionService);
  const router = inject(Router);
  const holdId = route.paramMap.get('holdId');
  const session = checkout.read();
  if (!holdId || !session || session.holdId !== holdId) {
    return router.createUrlTree(['/']);
  }
  if (session.passengers.length === 0 || session.passengers.some((passenger) => !passenger.fullName.trim())) {
    return router.createUrlTree(['/checkout', holdId, 'passengers']);
  }
  return true;
};
