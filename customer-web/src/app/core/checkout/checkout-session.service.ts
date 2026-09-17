import { Injectable } from '@angular/core';
import { TripSearchResult } from '../api/models';
import { locationLabel } from '../../shared/format';

export interface CheckoutSeat {
  inventoryId: string;
  seatNumber: string;
  seatType: string;
}

export interface CheckoutPassenger {
  seatInventoryId: string;
  fullName: string;
  age: number | null;
  gender: string | null;
}

export interface CheckoutTripSnapshot {
  operatorName: string;
  busDisplayName: string | null;
  busRegistrationNumber: string;
  routeName: string;
  routeCode: string;
  serviceDate: string;
  scheduledDepartureAt: string;
  scheduledArrivalAt: string;
  timeZone: string;
  originLabel: string;
  destinationLabel: string;
  baseFare: number;
  currency: string;
}

export interface CheckoutSession {
  holdId: string;
  tripId: string;
  originStopId: string;
  destinationStopId: string;
  expiresAt: string;
  seats: CheckoutSeat[];
  passengers: CheckoutPassenger[];
  bookingIdempotencyKey: string;
  paymentIdempotencyKey: string | null;
  bookingId: string | null;
  tripSnapshot: CheckoutTripSnapshot | null;
}

const KEY = 'blue-bus.checkout';
const SNAPSHOT_KEY = 'blue-bus.trip-snapshot';

@Injectable({ providedIn: 'root' })
export class CheckoutSessionService {
  read(): CheckoutSession | null {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) {
      return null;
    }
    try {
      return JSON.parse(raw) as CheckoutSession;
    } catch {
      sessionStorage.removeItem(KEY);
      return null;
    }
  }

  write(session: CheckoutSession): void {
    sessionStorage.setItem(KEY, JSON.stringify(session));
  }

  patch(update: Partial<CheckoutSession>): CheckoutSession | null {
    const current = this.read();
    if (!current) {
      return null;
    }
    const next = { ...current, ...update };
    this.write(next);
    return next;
  }

  clear(): void {
    sessionStorage.removeItem(KEY);
  }

  saveTripSnapshot(trip: TripSearchResult): void {
    const snapshot: CheckoutTripSnapshot = {
      operatorName: trip.operatorName,
      busDisplayName: trip.busDisplayName,
      busRegistrationNumber: trip.busRegistrationNumber,
      routeName: trip.routeName,
      routeCode: trip.routeCode,
      serviceDate: trip.serviceDate,
      scheduledDepartureAt: trip.scheduledDepartureAt,
      scheduledArrivalAt: trip.scheduledArrivalAt,
      timeZone: trip.timeZone,
      originLabel: locationLabel(trip.origin),
      destinationLabel: locationLabel(trip.destination),
      baseFare: trip.baseFare,
      currency: trip.currency
    };
    sessionStorage.setItem(SNAPSHOT_KEY, JSON.stringify({ tripId: trip.tripId, snapshot }));
  }

  tripSnapshot(tripId: string): CheckoutTripSnapshot | null {
    const raw = sessionStorage.getItem(SNAPSHOT_KEY);
    if (!raw) {
      return null;
    }
    try {
      const parsed = JSON.parse(raw) as { tripId: string; snapshot: CheckoutTripSnapshot };
      return parsed.tripId === tripId ? parsed.snapshot : null;
    } catch {
      return null;
    }
  }

  paymentKeyFor(bookingId: string): string {
    const current = this.read();
    if (current?.bookingId === bookingId && current.paymentIdempotencyKey) {
      return current.paymentIdempotencyKey;
    }
    return '';
  }
}
