import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../environments/environment';
import { operatorMembershipFixture } from '../../../testing/operator-fixtures';
import { OperatorContextService } from './operator-context.service';

describe('OperatorContextService', () => {
  let context: OperatorContextService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    context = TestBed.inject(OperatorContextService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('represents zero memberships without selecting an operator', () => {
    context.loadMemberships().subscribe();
    http.expectOne(`${environment.apiBaseUrl}/auth/operator-memberships`).flush([]);

    expect(context.loaded()).toBeTrue();
    expect(context.memberships()).toEqual([]);
    expect(context.selectedOperatorId()).toBeNull();
    expect(context.currentMembership()).toBeNull();
  });

  it('automatically selects the only operator membership', () => {
    const membership = operatorMembershipFixture();
    context.loadMemberships().subscribe();
    http
      .expectOne(`${environment.apiBaseUrl}/auth/operator-memberships`)
      .flush([membership]);

    expect(context.selectedOperatorId()).toBe(membership.operatorId);
    expect(context.currentMembership()).toEqual(membership);
    expect(context.hasMultipleMemberships()).toBeFalse();
  });

  it('requires an explicit valid selection for multiple memberships', () => {
    const first = operatorMembershipFixture();
    const second = operatorMembershipFixture({
      operatorId: 'operator-2',
      operatorDisplayName: 'Inland Express',
      role: 'OPERATOR_STAFF'
    });
    context.loadMemberships().subscribe();
    http
      .expectOne(`${environment.apiBaseUrl}/auth/operator-memberships`)
      .flush([first, second]);

    expect(context.selectedOperatorId()).toBeNull();
    expect(context.hasMultipleMemberships()).toBeTrue();
    expect(context.selectOperator('not-a-membership')).toBeFalse();
    expect(context.selectOperator(second.operatorId)).toBeTrue();
    expect(context.currentMembership()).toEqual(second);
  });

  it('clears a stale selection when refreshed memberships no longer contain it', () => {
    const first = operatorMembershipFixture();
    const revoked = operatorMembershipFixture({
      operatorId: 'operator-2',
      operatorDisplayName: 'Former Operator'
    });
    const second = operatorMembershipFixture({
      operatorId: 'operator-3',
      operatorDisplayName: 'Current Operator',
      role: 'OPERATOR_STAFF'
    });
    context.loadMemberships().subscribe();
    http
      .expectOne(`${environment.apiBaseUrl}/auth/operator-memberships`)
      .flush([first, revoked, second]);
    expect(context.selectOperator(revoked.operatorId)).toBeTrue();

    context.loadMemberships(true).subscribe();
    http
      .expectOne(`${environment.apiBaseUrl}/auth/operator-memberships`)
      .flush([first, second]);

    expect(context.selectedOperatorId()).toBeNull();
    expect(context.currentMembership()).toBeNull();
  });
});
