import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, catchError, finalize, map, of, share, switchMap, tap, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CustomerIdentity,
  LoginRequest,
  LoginResponse,
  RegisterCustomerRequest
} from '../api/models';
import { TokenStorageService } from './token-storage.service';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly tokens = inject(TokenStorageService);
  private readonly router = inject(Router);
  private readonly base = environment.apiBaseUrl;

  private readonly user = signal<CustomerIdentity | null>(null);
  private refreshInFlight: Observable<string> | null = null;

  readonly currentUser = this.user.asReadonly();
  readonly isAuthenticated = computed(() => this.user() !== null);
  readonly accessToken = this.tokens.accessToken;
  readonly hasRefreshToken = computed(() => !!this.tokens.refreshToken());

  restoreSession(): Observable<CustomerIdentity | null> {
    if (this.tokens.accessToken()) {
      return this.loadMe().pipe(catchError(() => this.refreshAndLoadMe()));
    }
    if (this.tokens.refreshToken()) {
      return this.refreshAndLoadMe();
    }
    return of(null);
  }

  login(request: LoginRequest): Observable<CustomerIdentity> {
    return this.http.post<LoginResponse>(`${this.base}/auth/login`, request).pipe(
      tap((response) => this.tokens.save(response.accessToken, response.refreshToken)),
      switchMap(() => this.loadMe())
    );
  }

  register(request: RegisterCustomerRequest): Observable<CustomerIdentity> {
    return this.http.post<CustomerIdentity>(`${this.base}/auth/register`, request);
  }

  logout(): Observable<void> {
    const refreshToken = this.tokens.refreshToken();
    const finish = (): Observable<void> => {
      this.clearSession();
      return of(void 0);
    };
    if (!refreshToken) {
      return finish();
    }
    return this.http.post<void>(`${this.base}/auth/logout`, { refreshToken }).pipe(
      catchError(() => of(void 0)),
      switchMap(() => finish())
    );
  }

  logoutAndRedirect(returnUrl = '/'): void {
    this.logout().subscribe(() => {
      void this.router.navigate(['/login'], { queryParams: { returnUrl } });
    });
  }

  refreshSession(): Observable<string> {
    const refreshToken = this.tokens.refreshToken();
    if (!refreshToken) {
      this.clearSession();
      return throwError(() => new Error('No refresh token'));
    }
    if (this.refreshInFlight) {
      return this.refreshInFlight;
    }
    this.refreshInFlight = this.http
      .post<LoginResponse>(`${this.base}/auth/refresh`, { refreshToken })
      .pipe(
        tap((response) => this.tokens.save(response.accessToken, response.refreshToken)),
        map((response) => response.accessToken),
        catchError((error) => {
          this.clearSession();
          return throwError(() => error);
        }),
        finalize(() => {
          this.refreshInFlight = null;
        }),
        share()
      );
    return this.refreshInFlight;
  }

  clearSession(): void {
    this.tokens.clear();
    this.user.set(null);
  }

  private refreshAndLoadMe(): Observable<CustomerIdentity | null> {
    return this.refreshSession().pipe(
      switchMap(() => this.loadMe()),
      catchError(() => of(null))
    );
  }

  private loadMe(): Observable<CustomerIdentity> {
    return this.http.get<CustomerIdentity>(`${this.base}/auth/me`).pipe(
      tap((identity) => this.user.set(identity))
    );
  }
}
