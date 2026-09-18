import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { TokenStorageService } from './token-storage.service';
import { identityFixture } from '../../../testing/fixtures';
import { environment } from '../../../environments/environment';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let tokens: TokenStorageService;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting()
      ]
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    tokens = TestBed.inject(TokenStorageService);
  });

  afterEach(() => {
    httpMock.verify();
    sessionStorage.clear();
  });

  it('attaches a Bearer access token to customer API calls', () => {
    tokens.save('access-1', 'refresh-1');
    http.get(`${environment.apiBaseUrl}/bookings`).subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/bookings`);
    expect(req.request.headers.get('Authorization')).toBe('Bearer access-1');
    req.flush([]);
  });

  it('does not attach a token to public login requests', () => {
    tokens.save('access-1', 'refresh-1');
    http.post(`${environment.apiBaseUrl}/auth/login`, { email: 'a@b.com', password: 'x' }).subscribe();
    const req = httpMock.expectOne(`${environment.apiBaseUrl}/auth/login`);
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({ accessToken: 'a', tokenType: 'Bearer', expiresIn: 1, refreshToken: 'r' });
  });

  it('refreshes once after 401 and retries the original request', () => {
    tokens.save('expired', 'refresh-1');
    http.get(`${environment.apiBaseUrl}/bookings`).subscribe();

    const first = httpMock.expectOne(`${environment.apiBaseUrl}/bookings`);
    first.flush({ message: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });

    const refresh = httpMock.expectOne(`${environment.apiBaseUrl}/auth/refresh`);
    expect(refresh.request.body).toEqual({ refreshToken: 'refresh-1' });
    refresh.flush({
      accessToken: 'access-2',
      tokenType: 'Bearer',
      expiresIn: 900,
      refreshToken: 'refresh-2'
    });

    const retry = httpMock.expectOne(`${environment.apiBaseUrl}/bookings`);
    expect(retry.request.headers.get('Authorization')).toBe('Bearer access-2');
    retry.flush([]);
    expect(tokens.accessToken()).toBe('access-2');
  });

  it('clears the session when refresh fails after 401', () => {
    tokens.save('expired', 'refresh-1');
    http.get(`${environment.apiBaseUrl}/auth/me`).subscribe({
      next: () => fail('expected error'),
      error: () => undefined
    });

    httpMock.expectOne(`${environment.apiBaseUrl}/auth/me`).flush(
      { message: 'Unauthorized' },
      { status: 401, statusText: 'Unauthorized' }
    );
    httpMock.expectOne(`${environment.apiBaseUrl}/auth/refresh`).flush(
      { message: 'Unauthorized' },
      { status: 401, statusText: 'Unauthorized' }
    );
    expect(tokens.accessToken()).toBeNull();
    expect(identityFixture.email).toBe('asha@example.com');
  });

  it('propagates a 401 when no refresh token is available', () => {
    tokens.save('expired', '');
    let status = 0;
    http.get(`${environment.apiBaseUrl}/operator/operator-1`).subscribe({
      next: () => fail('expected error'),
      error: (error) => {
        status = error.status;
      }
    });

    httpMock.expectOne(`${environment.apiBaseUrl}/operator/operator-1`).flush(
      { message: 'Unauthorized' },
      { status: 401, statusText: 'Unauthorized' }
    );
    httpMock.expectNone(`${environment.apiBaseUrl}/auth/refresh`);
    expect(status).toBe(401);
    expect(tokens.accessToken()).toBeNull();
  });
});
