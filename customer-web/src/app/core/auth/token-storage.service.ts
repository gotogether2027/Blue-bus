import { Injectable, signal } from '@angular/core';

const ACCESS_KEY = 'blue-bus.access-token';
const REFRESH_KEY = 'blue-bus.refresh-token';

/**
 * Access and refresh tokens are stored in sessionStorage.
 * The backend is a Bearer JWT API with CORS allowCredentials=false, so HttpOnly
 * cookies are not available without a backend auth change.
 */
@Injectable({ providedIn: 'root' })
export class TokenStorageService {
  private readonly access = signal<string | null>(sessionStorage.getItem(ACCESS_KEY));
  private readonly refresh = signal<string | null>(sessionStorage.getItem(REFRESH_KEY));

  accessToken = this.access.asReadonly();
  refreshToken = this.refresh.asReadonly();

  save(accessToken: string, refreshToken: string): void {
    sessionStorage.setItem(ACCESS_KEY, accessToken);
    sessionStorage.setItem(REFRESH_KEY, refreshToken);
    this.access.set(accessToken);
    this.refresh.set(refreshToken);
  }

  clear(): void {
    sessionStorage.removeItem(ACCESS_KEY);
    sessionStorage.removeItem(REFRESH_KEY);
    this.access.set(null);
    this.refresh.set(null);
  }
}
