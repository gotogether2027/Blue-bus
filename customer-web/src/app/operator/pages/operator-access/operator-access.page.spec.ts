import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { environment } from '../../../../environments/environment';
import { operatorMembershipFixture } from '../../../../testing/operator-fixtures';
import { OperatorAccessPageComponent } from './operator-access.page';

describe('OperatorAccessPageComponent', () => {
  let http: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [OperatorAccessPageComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify());

  it('shows a clear no-operator-access state for zero memberships', () => {
    const fixture = TestBed.createComponent(OperatorAccessPageComponent);
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/auth/operator-memberships`).flush([]);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No operator access');
  });

  it('navigates directly to the only operator membership', () => {
    const navigate = spyOn(router, 'navigate').and.resolveTo(true);
    const fixture = TestBed.createComponent(OperatorAccessPageComponent);
    fixture.detectChanges();
    http
      .expectOne(`${environment.apiBaseUrl}/auth/operator-memberships`)
      .flush([operatorMembershipFixture()]);

    expect(navigate).toHaveBeenCalledWith(['/operator', 'operator-1'], {
      replaceUrl: true
    });
  });

  it('renders multiple memberships and lets the user select one', () => {
    const navigate = spyOn(router, 'navigate').and.resolveTo(true);
    const fixture = TestBed.createComponent(OperatorAccessPageComponent);
    fixture.detectChanges();
    http
      .expectOne(`${environment.apiBaseUrl}/auth/operator-memberships`)
      .flush([
        operatorMembershipFixture(),
        operatorMembershipFixture({
          operatorId: 'operator-2',
          operatorDisplayName: 'Inland Express',
          role: 'OPERATOR_STAFF'
        })
      ]);
    fixture.detectChanges();

    const buttons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>(
        '.operator-selector-card'
      )
    );
    expect(buttons.length).toBe(2);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Inland Express');
    buttons[1].click();
    expect(navigate).toHaveBeenCalledWith(['/operator', 'operator-2']);
  });
});
