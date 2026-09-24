import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { Component, WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { environment } from '../../../environments/environment';
import { operatorMemberFixture } from '../../../testing/operator-fixtures';
import { routes } from '../../app.routes';
import { AuthService } from '../../core/auth/auth.service';
import { OPERATOR_ROUTES } from '../operator.routes';
import { OperatorContextService } from '../services/operator-context.service';
import { OperatorMembersPageComponent } from './operator-members/operator-members.page';

@Component({
  standalone: true,
  template: ''
})
class TestLoginComponent {}

describe('operator team management', () => {
  const base = `${environment.apiBaseUrl}/operator`;
  const staffMember = operatorMemberFixture({
    userId: '22222222-2222-4222-8222-222222222222',
    email: 'staff.ops@example.test',
    firstName: 'Ravi',
    lastName: 'Nair',
    role: 'OPERATOR_STAFF',
    status: 'ACTIVE'
  });

  afterEach(() => {
    const http = TestBed.inject(HttpTestingController, null, { optional: true });
    http?.verify();
  });

  it('loads the member list with actual operator-safe fields', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    flushMembers(setup.http, 'operator-1', [operatorMemberFixture(), staffMember]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Asha Rao');
    expect(text).toContain('admin.ops@example.test');
    expect(text).toContain('11111111-1111-4111-8111-111111111111');
    expect(text).toContain('Operator admin');
    expect(text).toContain('ACTIVE');
    expect(text).toContain('Ravi Nair');
    expect(text).toContain('Add member');
    expect(text).toContain('Edit');
    expect(text).toContain('Deactivate');
    expect(text).not.toContain('passwordHash');
    expect(text).not.toContain('SUPER_ADMIN');
  });

  it('renders an empty member list without fabricating team data', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    flushMembers(setup.http, 'operator-1', []);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('No team members');
    expect(text).toContain('Add the first member');
  });

  it('filters members by search text without calling a list query API', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    flushMembers(setup.http, 'operator-1', [operatorMemberFixture(), staffMember]);
    fixture.componentInstance.searchQuery = 'ravi';
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Ravi Nair');
    expect(text).not.toContain('Asha Rao');
  });

  it('hides management controls from operator staff', async () => {
    const setup = await configure(false);
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    flushMembers(setup.http, 'operator-1', [operatorMemberFixture(), staffMember]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Asha Rao');
    expect(text).toContain('read-only');
    expect(text).not.toContain('Add member');
    expect(text).not.toContain('Edit');
    expect(text).not.toContain('Deactivate');
    expect((fixture.nativeElement as HTMLElement).querySelector('form')).toBeNull();
  });

  it('shows management controls to operator admins', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    flushMembers(setup.http, 'operator-1', [staffMember]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Add member');
    expect(text).toContain('Edit');
    expect(text).toContain('Deactivate');
  });

  it('validates add-member fields before posting', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    flushMembers(setup.http, 'operator-1', [operatorMemberFixture()]);

    fixture.componentInstance.startAdd();
    fixture.componentInstance.addForm.setValue({
      userId: 'not-a-uuid',
      role: 'OPERATOR_STAFF'
    });
    fixture.componentInstance.submitAdd();
    setup.http.expectNone(`${base}/operator-1/members`);
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain('Enter a valid user ID.');
    expect(Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('option')).map(
      (option) => option.getAttribute('value')
    )).not.toContain('CUSTOMER');
  });

  it('adds a member with the exact create contract and refreshes the list', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    flushMembers(setup.http, 'operator-1', [operatorMemberFixture()]);

    fixture.componentInstance.startAdd();
    fixture.componentInstance.addForm.setValue({
      userId: ` ${staffMember.userId} `,
      role: 'OPERATOR_STAFF'
    });
    fixture.componentInstance.submitAdd();

    const request = setup.http.expectOne(`${base}/operator-1/members`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      userId: staffMember.userId,
      role: 'OPERATOR_STAFF'
    });
    request.flush(staffMember);
    flushMembers(setup.http, 'operator-1', [operatorMemberFixture(), staffMember]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Team member added.');
    expect(text).toContain('Ravi Nair');
    expect(text).toContain('staff.ops@example.test');
  });

  it('edits a member with only supported PATCH fields', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    flushMembers(setup.http, 'operator-1', [staffMember]);

    fixture.componentInstance.startEdit(staffMember);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="dialog"]')).not.toBeNull();

    fixture.componentInstance.editForm.setValue({
      role: 'OPERATOR_ADMIN',
      status: 'ACTIVE'
    });
    fixture.componentInstance.submitEdit();
    const request = setup.http.expectOne(
      `${base}/operator-1/members/${staffMember.userId}`
    );
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ role: 'OPERATOR_ADMIN' });
    request.flush({ ...staffMember, role: 'OPERATOR_ADMIN' });
    flushMembers(setup.http, 'operator-1', [
      { ...staffMember, role: 'OPERATOR_ADMIN' }
    ]);
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain('Team member updated.');
    expect(pageText(fixture.nativeElement)).toContain('Operator admin');
  });

  it('requires deactivation confirmation before posting', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    flushMembers(setup.http, 'operator-1', [staffMember]);

    fixture.componentInstance.requestDeactivate(staffMember);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="dialog"]')).not.toBeNull();
    expect(pageText(fixture.nativeElement)).toContain('Deactivation removes this user');
    setup.http.expectNone(`${base}/operator-1/members/${staffMember.userId}/deactivate`);

    fixture.componentInstance.cancelDeactivate();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="dialog"]')).toBeNull();
  });

  it('deactivates a member after confirmation and refreshes the list', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    flushMembers(setup.http, 'operator-1', [staffMember]);

    fixture.componentInstance.requestDeactivate(staffMember);
    fixture.componentInstance.confirmDeactivate();
    const request = setup.http.expectOne(
      `${base}/operator-1/members/${staffMember.userId}/deactivate`
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush({ ...staffMember, status: 'INACTIVE' });
    flushMembers(setup.http, 'operator-1', [{ ...staffMember, status: 'INACTIVE' }]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Team member deactivated');
    expect(text).toContain('INACTIVE');
  });

  it('shows a last-admin 409 without treating the mutation as successful', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    flushMembers(setup.http, 'operator-1', [operatorMemberFixture()]);

    fixture.componentInstance.requestDeactivate(operatorMemberFixture());
    fixture.componentInstance.confirmDeactivate();
    setup.http
      .expectOne(
        `${base}/operator-1/members/${operatorMemberFixture().userId}/deactivate`
      )
      .flush(
        { message: 'Operator must retain at least one ACTIVE OPERATOR_ADMIN membership.' },
        { status: 409, statusText: 'Conflict' }
      );
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain(
      'Operator must retain at least one ACTIVE OPERATOR_ADMIN membership.'
    );
    expect(text).not.toContain('Team member deactivated');
    expect(fixture.componentInstance.members[0].status).toBe('ACTIVE');
  });

  it('handles a 403 after a previously accessible member list', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/members`).flush(
      {},
      { status: 403, statusText: 'Forbidden' }
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.members).toEqual([]);
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('handles a 404 when the operator members resource is no longer available', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/members`).flush(
      {},
      { status: 404, statusText: 'Not Found' }
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.members).toEqual([]);
    expect(pageText(fixture.nativeElement)).toContain('Resource not found');
  });

  it('does not request members when the selected operator is unavailable', async () => {
    const setup = await configure();
    setup.selectedOperatorId.set(null);
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();

    setup.http.expectNone((request) => request.url.includes('/members'));
    expect(fixture.componentInstance.members).toEqual([]);
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('clears the previous operator members before loading a newly selected operator', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    flushMembers(setup.http, 'operator-1', [operatorMemberFixture()]);
    expect(fixture.componentInstance.members[0].email).toBe('admin.ops@example.test');

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.members).toEqual([]);

    flushMembers(setup.http, 'operator-2', [
      operatorMemberFixture({
        email: 'inland.admin@example.test',
        firstName: 'Meera',
        lastName: 'Iyer'
      })
    ]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Meera Iyer');
    expect(text).not.toContain('admin.ops@example.test');
    expect(text).not.toContain('Asha Rao');
  });

  it('ignores a stale members response after the operator changes', async () => {
    const setup = await configure();
    const fixture = TestBed.createComponent(OperatorMembersPageComponent);
    fixture.detectChanges();
    const first = setup.http.expectOne(`${base}/operator-1/members`);

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.members).toEqual([]);

    first.flush([operatorMemberFixture()]);
    expect(pageText(fixture.nativeElement)).not.toContain('Asha Rao');

    flushMembers(setup.http, 'operator-2', [
      operatorMemberFixture({
        email: 'inland.admin@example.test',
        firstName: 'Meera',
        lastName: 'Iyer'
      })
    ]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Meera Iyer');
    expect(text).not.toContain('Asha Rao');
  });

  it('exposes the members route under the operator membership guard', () => {
    const operatorArea = OPERATOR_ROUTES.find((route) => route.path === ':operatorId');
    const members = operatorArea?.children?.find((route) => route.path === 'members');

    expect(operatorArea?.canActivate).toBeDefined();
    expect(members?.path).toBe('members');
    expect(members?.canActivate).toBeUndefined();
  });

  it('leaves customer booking and payment routes unchanged', () => {
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
    const clearSession = jasmine.createSpy('clearSession');

    await TestBed.configureTestingModule({
      imports: [OperatorMembersPageComponent],
      providers: [
        provideRouter([{ path: 'login', component: TestLoginComponent }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: OperatorContextService,
          useValue: {
            selectedOperatorId: selectedOperatorId.asReadonly(),
            canManageOperator: canManageOperator.asReadonly()
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

  function flushMembers(
    http: HttpTestingController,
    operatorId: string,
    members: ReturnType<typeof operatorMemberFixture>[]
  ): void {
    http.expectOne(`${base}/${operatorId}/members`).flush(members);
  }

  function pageText(element: HTMLElement): string {
    return element.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }
});
