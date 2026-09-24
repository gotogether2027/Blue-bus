import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { Component, WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { environment } from '../../../environments/environment';
import {
  operatorMembershipFixture,
  operatorProfileFixture
} from '../../../testing/operator-fixtures';
import { routes } from '../../app.routes';
import { AuthService } from '../../core/auth/auth.service';
import { OperatorMembership } from '../models/operator.models';
import { OPERATOR_ROUTES } from '../operator.routes';
import { OperatorContextService } from '../services/operator-context.service';
import { OperatorSettingsPageComponent } from './operator-settings/operator-settings.page';

@Component({
  standalone: true,
  template: ''
})
class TestLoginComponent {}

describe('operator workspace settings', () => {
  const base = `${environment.apiBaseUrl}/operator`;

  afterEach(() => {
    const http = TestBed.inject(HttpTestingController, null, { optional: true });
    http?.verify();
  });

  it('loads the operator profile using the selected operator ID', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();
    const request = setup.http.expectOne(`${base}/operator-1`);
    expect(request.request.method).toBe('GET');
    request.flush(operatorProfileFixture());
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const text = pageText(element);
    expect(text).toContain('Coastal Travels');
    expect(text).toContain('Coastal Travels Private Limited');
    expect(text).toContain('operator-1');
    expect(text).toContain('ACTIVE');
    expect(text).toContain('Save support contact');
    expect(inputValue(element, 'supportEmail')).toBe('ops@example.test');
    expect(inputValue(element, 'supportPhoneE164')).toBe('+919876543210');
    expect(text).not.toContain('passwordHash');
    expect(text).not.toContain('createdAt');
    expect(text).not.toContain('Activate operator');
    expect(text).not.toContain('Deactivate operator');
  });

  it('renders only API profile fields and workspace links', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();
    flushProfile(setup.http, 'operator-1');
    fixture.detectChanges();

    const hrefs = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>('a')
    ).map((anchor) => anchor.getAttribute('href'));
    expect(hrefs).toContain('/operator/operator-1');
    expect(hrefs).toContain('/operator/operator-1/members');
    expect(hrefs).toContain('/operator/operator-1/buses');
    expect(hrefs).toContain('/operator/operator-1/routes');
    expect(hrefs).toContain('/operator/operator-1/trips');
    expect(pageText(fixture.nativeElement)).toContain(
      'Inventory and bookings are opened from a trip'
    );
  });

  it('hides support-contact mutation controls from operator staff', async () => {
    const setup = await configure(false);
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();
    flushProfile(setup.http, 'operator-1');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('ops@example.test');
    expect(text).toContain('read-only');
    expect(text).not.toContain('Save support contact');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('input[formControlName="supportEmail"]')
    ).toBeNull();
  });

  it('shows a loading state until the profile response arrives', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.loading).toBeTrue();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[aria-label="Loading operator settings"]'
      )
    ).not.toBeNull();

    flushProfile(setup.http, 'operator-1');
    fixture.detectChanges();
    expect(fixture.componentInstance.loading).toBeFalse();
    expect(fixture.componentInstance.submitting).toBeFalse();
  });

  it('validates support contact fields before posting', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();
    flushProfile(setup.http, 'operator-1');

    fixture.componentInstance.form.setValue({
      supportEmail: 'not-an-email',
      supportPhoneE164: '9900000001'
    });
    fixture.componentInstance.submit();
    setup.http.expectNone(`${base}/operator-1`);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Enter a valid email address');
    expect(text).toContain('Enter a valid E.164 phone number');
  });

  it('does not patch when support contact values are unchanged', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();
    flushProfile(setup.http, 'operator-1');

    fixture.componentInstance.submit();
    setup.http.expectNone(`${base}/operator-1`);
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('No changes to save');
  });

  it('saves both current support fields on the exact PATCH contract', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();
    flushProfile(setup.http, 'operator-1');

    fixture.componentInstance.form.setValue({
      supportEmail: ' desk@example.test ',
      supportPhoneE164: ' +919812345678 '
    });
    fixture.componentInstance.submit();
    expect(fixture.componentInstance.submitting).toBeTrue();
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain('Saving…');

    const request = setup.http.expectOne(`${base}/operator-1`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({
      supportEmail: 'desk@example.test',
      supportPhoneE164: '+919812345678'
    });
    request.flush(
      operatorProfileFixture({
        supportEmail: 'desk@example.test',
        supportPhoneE164: '+919812345678'
      })
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.submitting).toBeFalse();
    expect(fixture.componentInstance.form.controls.supportEmail.value).toBe('desk@example.test');
    expect(fixture.componentInstance.form.controls.supportPhoneE164.value).toBe('+919812345678');
    expect(pageText(fixture.nativeElement)).toContain('Support contact updated.');
  });

  it('shows backend validation errors without leaving the form', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();
    flushProfile(setup.http, 'operator-1');

    fixture.componentInstance.form.setValue({
      supportEmail: 'desk@example.test',
      supportPhoneE164: '+919812345678'
    });
    fixture.componentInstance.submit();
    setup.http.expectOne(`${base}/operator-1`).flush(
      {
        message: 'Validation failed.',
        fieldViolations: [{ field: 'supportEmail', message: 'must be a well-formed email address' }]
      },
      { status: 400, statusText: 'Bad Request' }
    );
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(pageText(element)).toContain('must be a well-formed email address');
    expect(element.querySelector('form')).not.toBeNull();
    expect(fixture.componentInstance.operator?.supportEmail).toBe('ops@example.test');
  });

  it('shows a backend 409 without treating the mutation as successful', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();
    flushProfile(setup.http, 'operator-1');

    fixture.componentInstance.form.setValue({
      supportEmail: 'desk@example.test',
      supportPhoneE164: '+919812345678'
    });
    fixture.componentInstance.submit();
    setup.http.expectOne(`${base}/operator-1`).flush(
      { message: 'Support contact could not be updated.' },
      { status: 409, statusText: 'Conflict' }
    );
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Support contact could not be updated.');
    expect(text).not.toContain('Support contact updated.');
    expect(fixture.componentInstance.operator?.supportEmail).toBe('ops@example.test');
  });

  it('handles a 403 after a previously accessible profile', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1`).flush(
      {},
      { status: 403, statusText: 'Forbidden' }
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.operator).toBeNull();
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('handles a 404 when the operator is no longer available', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1`).flush(
      {},
      { status: 404, statusText: 'Not Found' }
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.operator).toBeNull();
    expect(pageText(fixture.nativeElement)).toContain('Resource not found');
  });

  it('does not request the profile when the selected operator is unavailable', async () => {
    const setup = await configure();
    setup.selectedOperatorId.set(null);
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();

    setup.http.expectNone(`${base}/operator-1`);
    expect(fixture.componentInstance.operator).toBeNull();
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('clears the previous operator profile before loading a newly selected operator', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();
    flushProfile(setup.http, 'operator-1');
    expect(fixture.componentInstance.operator?.displayName).toBe('Coastal Travels');

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.operator).toBeNull();

    flushProfile(
      setup.http,
      'operator-2',
      operatorProfileFixture({
        id: 'operator-2',
        displayName: 'Inland Express',
        legalName: 'Inland Express Private Limited',
        supportEmail: 'inland@example.test'
      })
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.operator?.id).toBe('operator-2');
    expect(fixture.componentInstance.form.controls.supportEmail.value).toBe('inland@example.test');
    expect(pageText(fixture.nativeElement)).toContain('Inland Express');
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Travels');
  });

  it('ignores a stale profile response after the operator changes', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();
    const first = setup.http.expectOne(`${base}/operator-1`);

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.operator).toBeNull();

    first.flush(operatorProfileFixture());
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Travels');

    flushProfile(
      setup.http,
      'operator-2',
      operatorProfileFixture({
        id: 'operator-2',
        displayName: 'Inland Express',
        legalName: 'Inland Express Private Limited',
        supportEmail: 'inland@example.test'
      })
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.operator?.id).toBe('operator-2');
    expect(fixture.componentInstance.form.controls.supportEmail.value).toBe('inland@example.test');
    expect(pageText(fixture.nativeElement)).toContain('Inland Express');
    expect(pageText(fixture.nativeElement)).not.toContain('Coastal Travels');
  });

  it('ignores a stale support-contact mutation after the operator changes', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorSettingsPageComponent);
    fixture.detectChanges();
    flushProfile(setup.http, 'operator-1');

    fixture.componentInstance.form.setValue({
      supportEmail: 'stale@example.test',
      supportPhoneE164: '+919800000001'
    });
    fixture.componentInstance.submit();
    const patch = setup.http.expectOne(`${base}/operator-1`);

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    const second = setup.http.expectOne(`${base}/operator-2`);

    patch.flush(
      operatorProfileFixture({
        supportEmail: 'stale@example.test',
        supportPhoneE164: '+919800000001'
      })
    );
    second.flush(
      operatorProfileFixture({
        id: 'operator-2',
        displayName: 'Inland Express',
        legalName: 'Inland Express Private Limited',
        supportEmail: 'inland@example.test'
      })
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.operator?.id).toBe('operator-2');
    expect(fixture.componentInstance.form.controls.supportEmail.value).toBe('inland@example.test');
    expect(pageText(fixture.nativeElement)).toContain('Inland Express');
    expect(pageText(fixture.nativeElement)).not.toContain('Support contact updated.');
  });

  it('exposes settings under the operator membership guard and keeps existing operator routes', () => {
    const operatorArea = OPERATOR_ROUTES.find((route) => route.path === ':operatorId');
    const childPaths = (operatorArea?.children ?? []).map((route) => route.path);

    expect(operatorArea?.canActivate).toBeDefined();
    expect(childPaths).toContain('settings');
    expect(childPaths).toContain('members');
    expect(childPaths).toContain('buses');
    expect(childPaths).toContain('routes');
    expect(childPaths).toContain('trips');
    expect(childPaths).toContain('trips/:tripId/inventory');
    expect(childPaths).toContain('trips/:tripId/bookings');
    expect(operatorArea?.children?.find((route) => route.path === 'settings')?.canActivate).toBeUndefined();
  });

  it('leaves customer profile and booking/payment routes unchanged', () => {
    const customerShell = routes.find((route) => route.path === '');
    const customerPaths = (customerShell?.children ?? []).map((route) => route.path);

    expect(customerPaths).toEqual([
      '',
      'search',
      'login',
      'register',
      'bookings',
      'bookings/:bookingId/confirmation',
      'bookings/:bookingId/ticket',
      'bookings/:bookingId',
      'profile',
      'trips/:tripId/seats',
      'checkout/:holdId/passengers',
      'checkout/:holdId/review',
      'payment/:bookingId'
    ]);
  });

  async function configure(canManage = true): Promise<{
    http: HttpTestingController;
    selectedOperatorId: WritableSignal<string | null>;
    clearSession: jasmine.Spy<() => void>;
    navigate: Router['navigate'];
  }> {
    const selectedOperatorId = signal<string | null>('operator-1');
    const canManageOperator = signal(canManage);
    const membership = signal<OperatorMembership | null>(
      operatorMembershipFixture({
        role: canManage ? 'OPERATOR_ADMIN' : 'OPERATOR_STAFF'
      })
    );
    const clearSession = jasmine.createSpy('clearSession');

    await TestBed.configureTestingModule({
      imports: [OperatorSettingsPageComponent],
      providers: [
        provideRouter([{ path: 'login', component: TestLoginComponent }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: OperatorContextService,
          useValue: {
            selectedOperatorId: selectedOperatorId.asReadonly(),
            canManageOperator: canManageOperator.asReadonly(),
            currentMembership: membership.asReadonly()
          }
        },
        {
          provide: AuthService,
          useValue: { clearSession }
        }
      ]
    }).compileComponents();

    const http = TestBed.inject(HttpTestingController);
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate').and.resolveTo(true);
    return { http, selectedOperatorId, clearSession, navigate };
  }

  function flushProfile(
    http: HttpTestingController,
    operatorId: string,
    profile = operatorProfileFixture({ id: operatorId })
  ): void {
    http.expectOne(`${base}/${operatorId}`).flush(profile);
  }

  function pageText(element: HTMLElement): string {
    return element.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  function inputValue(element: HTMLElement, controlName: string): string {
    return (
      element.querySelector<HTMLInputElement>(`input[formControlName="${controlName}"]`)?.value ??
      ''
    );
  }
});
