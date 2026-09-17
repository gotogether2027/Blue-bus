import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { AuthService } from './auth.service';
import { TokenStorageService } from './token-storage.service';
import { identityFixture } from '../../../testing/fixtures';
import { environment } from '../../../environments/environment';

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;
  let tokens: TokenStorageService;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    tokens = TestBed.inject(TokenStorageService);
  });

  afterEach(() => {
    http.verify();
    sessionStorage.clear();
  });

  it('stores tokens and loads the current user after login', () => {
    let userEmail = '';
    service.login({ email: 'asha@example.com', password: 'Secret123' }).subscribe((user) => {
      userEmail = user.email;
    });

    const login = http.expectOne(`${environment.apiBaseUrl}/auth/login`);
    expect(login.request.body).toEqual({ email: 'asha@example.com', password: 'Secret123' });
    login.flush({
      accessToken: 'access-1',
      tokenType: 'Bearer',
      expiresIn: 900,
      refreshToken: 'refresh-1'
    });

    const me = http.expectOne(`${environment.apiBaseUrl}/auth/me`);
    expect(me.request.method).toBe('GET');
    me.flush(identityFixture);

    expect(userEmail).toBe('asha@example.com');
    expect(tokens.accessToken()).toBe('access-1');
    expect(tokens.refreshToken()).toBe('refresh-1');
    expect(service.isAuthenticated()).toBeTrue();
  });

  it('registers with the backend identity contract and does not store tokens', () => {
    service
      .register({
        firstName: 'Asha',
        lastName: 'Rao',
        email: 'asha@example.com',
        password: 'Secret123'
      })
      .subscribe();

    const req = http.expectOne(`${environment.apiBaseUrl}/auth/register`);
    expect(req.request.body).toEqual({
      firstName: 'Asha',
      lastName: 'Rao',
      email: 'asha@example.com',
      password: 'Secret123'
    });
    req.flush(identityFixture);
    expect(tokens.accessToken()).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
  });

  it('logs out with the refresh token and clears session storage', () => {
    tokens.save('access-1', 'refresh-1');
    service.logout().subscribe();

    const req = http.expectOne(`${environment.apiBaseUrl}/auth/logout`);
    expect(req.request.body).toEqual({ refreshToken: 'refresh-1' });
    req.flush(null);
    expect(tokens.accessToken()).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
  });

  it('restores a session from a stored access token', () => {
    tokens.save('access-1', 'refresh-1');
    let restored = false;
    service.restoreSession().subscribe((user) => {
      restored = user?.email === identityFixture.email;
    });
    http.expectOne(`${environment.apiBaseUrl}/auth/me`).flush(identityFixture);
    expect(restored).toBeTrue();
    expect(service.currentUser()?.userId).toBe('user-1');
  });
});
