import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { CustomerIdentity } from '../../../core/api/models';
import { AuthService } from '../../../core/auth/auth.service';
import { identityFixture } from '../../../../testing/fixtures';
import { operatorMembershipFixture } from '../../../../testing/operator-fixtures';
import { OperatorMembership } from '../../models/operator.models';
import { OperatorContextService } from '../../services/operator-context.service';
import { OperatorShellComponent } from './operator-shell.page';

describe('OperatorShellComponent', () => {
  let fixture: ComponentFixture<OperatorShellComponent>;
  let router: Router;
  const user = signal<CustomerIdentity | null>(identityFixture);
  const memberships = signal<OperatorMembership[]>([
    operatorMembershipFixture(),
    operatorMembershipFixture({
      operatorId: 'operator-2',
      operatorDisplayName: 'Inland Express',
      role: 'OPERATOR_STAFF'
    })
  ]);
  const selectedOperatorId = signal<string | null>('operator-1');
  const currentMembership = signal<OperatorMembership | null>(memberships()[0]);

  beforeEach(async () => {
    selectedOperatorId.set('operator-1');
    currentMembership.set(memberships()[0]);
    await TestBed.configureTestingModule({
      imports: [OperatorShellComponent],
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            currentUser: user.asReadonly(),
            logout: () => of(void 0)
          }
        },
        {
          provide: OperatorContextService,
          useValue: {
            memberships: memberships.asReadonly(),
            selectedOperatorId: selectedOperatorId.asReadonly(),
            currentMembership: currentMembership.asReadonly(),
            hasMultipleMemberships: () => memberships().length > 1,
            reset: jasmine.createSpy('reset')
          }
        }
      ]
    }).compileComponents();
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(OperatorShellComponent);
    fixture.detectChanges();
  });

  it('renders dashboard, buses, and trips navigation for the selected operator', () => {
    const hrefs = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>(
        '.operator-nav a'
      )
    ).map((anchor) => anchor.getAttribute('href'));

    expect(hrefs).toContain('/operator/operator-1');
    expect(hrefs).toContain('/operator/operator-1/buses');
    expect(hrefs).toContain('/operator/operator-1/trips');
  });

  it('switches the shell to another current membership', () => {
    const navigate = spyOn(router, 'navigate').and.resolveTo(true);
    const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
      'select[aria-label="Current operator"]'
    );
    expect(select).not.toBeNull();

    select!.value = 'operator-2';
    select!.dispatchEvent(new Event('change'));

    expect(navigate).toHaveBeenCalledWith(['/operator', 'operator-2']);
  });
});
