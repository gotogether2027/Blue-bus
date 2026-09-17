import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { CustomerIdentity } from '../api/models';
import { identityFixture } from '../../../testing/fixtures';
import { authGuard, guestGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('auth guards', () => {
  const user = signal<CustomerIdentity | null>(identityFixture);

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => user() !== null,
            currentUser: user.asReadonly()
          }
        }
      ]
    });
  });

  it('allows authenticated users into protected routes', () => {
    user.set(identityFixture);
    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as never, { url: '/bookings' } as never)
    );
    expect(result).toBeTrue();
  });

  it('redirects anonymous users to login with a return URL', () => {
    user.set(null);
    const result = TestBed.runInInjectionContext(() =>
      authGuard({} as never, { url: '/bookings' } as never)
    );
    const router = TestBed.inject(Router);
    expect(result).toEqual(router.createUrlTree(['/login'], { queryParams: { returnUrl: '/bookings' } }));
  });

  it('keeps guests on login and sends authenticated users home', () => {
    user.set(null);
    const guestOk = TestBed.runInInjectionContext(() => guestGuard({} as never, {} as never));
    expect(guestOk).toBeTrue();

    user.set(identityFixture);
    const guestBlocked = TestBed.runInInjectionContext(() => guestGuard({} as never, {} as never));
    const router = TestBed.inject(Router);
    expect(guestBlocked).toEqual(router.createUrlTree(['/']));
  });
});
