import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { environment } from '../../../environments/environment';
import { identityFixture } from '../../../testing/fixtures';
import { operatorMembershipFixture } from '../../../testing/operator-fixtures';
import { CustomerIdentity } from '../api/models';
import { AuthService } from '../auth/auth.service';
import { AppShellComponent } from './app-shell.component';

describe('AppShellComponent operator navigation', () => {
  const currentUser = signal<CustomerIdentity | null>(identityFixture);
  let http: HttpTestingController;

  beforeEach(async () => {
    currentUser.set(identityFixture);
    await TestBed.configureTestingModule({
      imports: [AppShellComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            currentUser: currentUser.asReadonly(),
            isAuthenticated: () => currentUser() !== null,
            logout: () => of(void 0)
          }
        }
      ]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('does not advertise the operator portal when membership discovery is empty', () => {
    const fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();
    http
      .expectOne(`${environment.apiBaseUrl}/auth/operator-memberships`)
      .flush([]);
    fixture.detectChanges();

    expect(operatorLink(fixture.nativeElement as HTMLElement)).toBeNull();
  });

  it('shows the operator portal only after membership discovery succeeds', () => {
    const fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();
    http
      .expectOne(`${environment.apiBaseUrl}/auth/operator-memberships`)
      .flush([operatorMembershipFixture()]);
    fixture.detectChanges();

    expect(operatorLink(fixture.nativeElement as HTMLElement)?.getAttribute('href')).toBe(
      '/operator'
    );
  });

  function operatorLink(host: HTMLElement): HTMLAnchorElement | null {
    return (
      Array.from(host.querySelectorAll<HTMLAnchorElement>('nav a')).find(
        (anchor) => anchor.textContent?.trim() === 'Operator portal'
      ) ?? null
    );
  }
});
