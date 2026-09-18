import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { Type, WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRoute,
  Router,
  convertToParamMap,
  provideRouter
} from '@angular/router';
import { environment } from '../../../environments/environment';
import {
  operatorBusFixture,
  operatorBusTypeFixture,
  operatorSeatLayoutFixture
} from '../../../testing/operator-fixtures';
import { routes } from '../../app.routes';
import { AuthService } from '../../core/auth/auth.service';
import { OperatorContextService } from '../services/operator-context.service';
import { OperatorBusCreatePageComponent } from './operator-bus-create/operator-bus-create.page';
import { OperatorBusDetailPageComponent } from './operator-bus-detail/operator-bus-detail.page';
import { OperatorBusEditPageComponent } from './operator-bus-edit/operator-bus-edit.page';
import { OperatorBusesPageComponent } from './operator-buses/operator-buses.page';

describe('operator bus management', () => {
  const base = `${environment.apiBaseUrl}/operator`;

  afterEach(() => {
    const http = TestBed.inject(HttpTestingController, null, { optional: true });
    http?.verify();
  });

  it('renders an empty bus list without fabricating fleet data', async () => {
    const setup = await configure(OperatorBusesPageComponent);
    const fixture = TestBed.createComponent(OperatorBusesPageComponent);
    fixture.detectChanges();
    flushBusCollection(setup.http, 'operator-1', []);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('No buses');
    expect(text).toContain('Create the first bus');
  });

  it('loads the bus list with actual operator-safe fields', async () => {
    const setup = await configure(OperatorBusesPageComponent);
    const fixture = TestBed.createComponent(OperatorBusesPageComponent);
    fixture.detectChanges();
    flushBusCollection(setup.http, 'operator-1', [operatorBusFixture()]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('AP31AB1234');
    expect(text).toContain('Coastal Sleeper');
    expect(text).toContain('AC Sleeper');
    expect(text).toContain('Sleeper 2+1');
    expect(text).toContain('ACTIVE');
    expect(text).toContain('View bus');
    expect(text).toContain('Create bus');
    expect(text).not.toContain('seat count');
    expect(text).not.toContain('occupancy');
  });

  it('hides list mutation controls from operator staff', async () => {
    const setup = await configure(OperatorBusesPageComponent, {}, false);
    const fixture = TestBed.createComponent(OperatorBusesPageComponent);
    fixture.detectChanges();
    flushBusCollection(setup.http, 'operator-1', [operatorBusFixture()]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('View bus');
    expect(text).toContain('read-only');
    expect(text).not.toContain('Create bus');
  });

  it('loads required active types and published layouts for the create form', async () => {
    const setup = await configure(OperatorBusCreatePageComponent);
    const fixture = TestBed.createComponent(OperatorBusCreatePageComponent);
    fixture.detectChanges();
    flushReferences(setup.http, 'operator-1');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('AC Sleeper');
    expect(text).toContain('Sleeper 2+1');
    expect(text).toContain('Only published layouts owned by this operator');
    expect(text).not.toContain('Other operator layout');
  });

  it('omits unpublished and other-operator layouts from the create form', async () => {
    const setup = await configure(OperatorBusCreatePageComponent);
    const fixture = TestBed.createComponent(OperatorBusCreatePageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/bus-types`).flush([
      operatorBusTypeFixture(),
      operatorBusTypeFixture({ id: 'bus-type-off', displayName: 'Retired Type', active: false })
    ]);
    setup.http
      .expectOne((request) => request.url === `${base}/operator-1/seat-layouts`)
      .flush([
        operatorSeatLayoutFixture(),
        operatorSeatLayoutFixture({
          id: 'layout-draft',
          name: 'Draft layout',
          status: 'DRAFT'
        }),
        operatorSeatLayoutFixture({
          id: 'layout-other',
          operatorId: 'operator-2',
          name: 'Other operator layout'
        })
      ]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Sleeper 2+1');
    expect(text).not.toContain('Retired Type');
    expect(text).not.toContain('Draft layout');
    expect(text).not.toContain('Other operator layout');
  });

  it('creates a bus and navigates to its operator-scoped detail page', async () => {
    const setup = await configure(OperatorBusCreatePageComponent);
    const fixture = TestBed.createComponent(OperatorBusCreatePageComponent);
    fixture.detectChanges();
    flushReferences(setup.http, 'operator-1');

    fixture.componentInstance.form.setValue({
      registrationNumber: ' AP31AB1234 ',
      displayName: ' Coastal Sleeper ',
      busTypeId: 'bus-type-1',
      seatLayoutId: 'layout-1'
    });
    fixture.componentInstance.submit();

    const request = setup.http.expectOne(`${base}/operator-1/buses`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      registrationNumber: 'AP31AB1234',
      displayName: 'Coastal Sleeper',
      busTypeId: 'bus-type-1',
      seatLayoutId: 'layout-1'
    });
    request.flush(operatorBusFixture());

    expect(setup.navigate).toHaveBeenCalledWith(
      ['/operator', 'operator-1', 'buses', 'bus-1'],
      { queryParams: { created: 'true' } }
    );
  });

  it('shows the backend duplicate-registration conflict without a racy pre-check', async () => {
    const setup = await configure(OperatorBusCreatePageComponent);
    const fixture = TestBed.createComponent(OperatorBusCreatePageComponent);
    fixture.detectChanges();
    flushReferences(setup.http, 'operator-1');

    fixture.componentInstance.form.setValue({
      registrationNumber: 'AP31AB1234',
      displayName: '',
      busTypeId: 'bus-type-1',
      seatLayoutId: 'layout-1'
    });
    fixture.componentInstance.submit();
    setup.http.expectOne(`${base}/operator-1/buses`).flush(
      { message: 'Bus registration number already exists.' },
      { status: 409, statusText: 'Conflict' }
    );
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain(
      'Bus registration number already exists.'
    );
  });

  it('shows backend validation errors and keeps the create form available', async () => {
    const setup = await configure(OperatorBusCreatePageComponent);
    const fixture = TestBed.createComponent(OperatorBusCreatePageComponent);
    fixture.detectChanges();
    flushReferences(setup.http, 'operator-1');

    fixture.componentInstance.form.setValue({
      registrationNumber: 'AP31AB1234',
      displayName: '',
      busTypeId: 'bus-type-1',
      seatLayoutId: 'layout-1'
    });
    fixture.componentInstance.submit();
    setup.http.expectOne(`${base}/operator-1/buses`).flush(
      {
        message: 'Validation failed.',
        fieldViolations: [{ field: 'registrationNumber', message: 'must be valid' }]
      },
      { status: 400, statusText: 'Bad Request' }
    );
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(pageText(element)).toContain('must be valid');
    expect(element.querySelector('form')).not.toBeNull();
  });

  it('edits only mutable fields and never renders immutable inputs', async () => {
    const setup = await configure(OperatorBusEditPageComponent, { busId: 'bus-1' });
    const fixture = TestBed.createComponent(OperatorBusEditPageComponent);
    fixture.detectChanges();
    flushBusDetail(setup.http, 'operator-1');
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('input[formControlName="registrationNumber"]')).toBeNull();
    expect(element.querySelector('[formControlName="operatorId"]')).toBeNull();
    expect(pageText(element)).toContain('AP31AB1234');

    fixture.componentInstance.form.controls.displayName.setValue('Night Rider');
    fixture.componentInstance.submit();
    const request = setup.http.expectOne(`${base}/operator-1/buses/bus-1`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ displayName: 'Night Rider' });
    request.flush(operatorBusFixture({ displayName: 'Night Rider' }));

    expect(setup.navigate).toHaveBeenCalledWith(
      ['/operator', 'operator-1', 'buses', 'bus-1'],
      { queryParams: { updated: 'true' } }
    );
  });

  it('shows a seat-layout trip conflict returned by the backend', async () => {
    const setup = await configure(OperatorBusEditPageComponent, { busId: 'bus-1' });
    const fixture = TestBed.createComponent(OperatorBusEditPageComponent);
    fixture.detectChanges();
    flushBusDetail(setup.http, 'operator-1', [
      operatorSeatLayoutFixture(),
      operatorSeatLayoutFixture({ id: 'layout-2', name: 'Seater 2+2' })
    ]);

    fixture.componentInstance.form.controls.seatLayoutId.setValue('layout-2');
    fixture.componentInstance.submit();
    setup.http.expectOne(`${base}/operator-1/buses/bus-1`).flush(
      { message: 'Seat layout cannot be changed while trips exist for this bus.' },
      { status: 409, statusText: 'Conflict' }
    );
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain(
      'Seat layout cannot be changed while trips exist for this bus.'
    );
  });

  it('loads the complete operator-safe bus detail DTO', async () => {
    const setup = await configure(OperatorBusDetailPageComponent, { busId: 'bus-1' });
    const fixture = TestBed.createComponent(OperatorBusDetailPageComponent);
    fixture.detectChanges();
    flushBusDetail(setup.http, 'operator-1');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('AP31AB1234');
    expect(text).toContain('Coastal Sleeper');
    expect(text).toContain('AC Sleeper');
    expect(text).toContain('Sleeper 2+1');
    expect(text).toContain('ACTIVE');
    expect(text).toContain('bus-1');
    expect(text).toContain('operator-1');
    expect(text).toContain('bus-type-1');
    expect(text).toContain('layout-1');
  });

  it('shows mutation controls to admins', async () => {
    const setup = await configure(OperatorBusDetailPageComponent, { busId: 'bus-1' });
    const fixture = TestBed.createComponent(OperatorBusDetailPageComponent);
    fixture.detectChanges();
    flushBusDetail(setup.http, 'operator-1');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Edit bus');
    expect(text).toContain('Mark as maintenance');
    expect(text).toContain('Deactivate bus');
  });

  it('keeps operator staff read-only', async () => {
    const setup = await configure(
      OperatorBusDetailPageComponent,
      { busId: 'bus-1' },
      false
    );
    const fixture = TestBed.createComponent(OperatorBusDetailPageComponent);
    fixture.detectChanges();
    flushBusDetail(setup.http, 'operator-1');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('OPERATOR_STAFF membership provides read-only bus access');
    expect(text).not.toContain('Edit bus');
    expect(text).not.toContain('Deactivate bus');
    expect(text).not.toContain('Mark as maintenance');
  });

  it('requires lifecycle confirmation before sending a mutation', async () => {
    const setup = await configure(OperatorBusDetailPageComponent, { busId: 'bus-1' });
    const fixture = TestBed.createComponent(OperatorBusDetailPageComponent);
    fixture.detectChanges();
    flushBusDetail(setup.http, 'operator-1');

    fixture.componentInstance.requestLifecycle('deactivate');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="dialog"]')).not.toBeNull();
    setup.http.expectNone(`${base}/operator-1/buses/bus-1/deactivate`);

    fixture.componentInstance.cancelLifecycle();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="dialog"]')).toBeNull();
  });

  it('applies a confirmed lifecycle change and refreshes the displayed DTO', async () => {
    const setup = await configure(OperatorBusDetailPageComponent, { busId: 'bus-1' });
    const fixture = TestBed.createComponent(OperatorBusDetailPageComponent);
    fixture.detectChanges();
    flushBusDetail(setup.http, 'operator-1');

    fixture.componentInstance.requestLifecycle('maintenance');
    fixture.componentInstance.confirmLifecycle();
    const request = setup.http.expectOne(
      `${base}/operator-1/buses/bus-1/maintenance`
    );
    expect(request.request.method).toBe('POST');
    request.flush(operatorBusFixture({ status: 'MAINTENANCE' }));
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Bus marked as under maintenance.');
    expect(text).toContain('MAINTENANCE');
  });

  it('does not automatically retry a lifecycle mutation', async () => {
    const setup = await configure(OperatorBusDetailPageComponent, { busId: 'bus-1' });
    const fixture = TestBed.createComponent(OperatorBusDetailPageComponent);
    fixture.detectChanges();
    flushBusDetail(setup.http, 'operator-1');

    fixture.componentInstance.requestLifecycle('deactivate');
    fixture.componentInstance.confirmLifecycle();
    fixture.componentInstance.confirmLifecycle();
    const requests = setup.http.match(`${base}/operator-1/buses/bus-1/deactivate`);
    expect(requests.length).toBe(1);
    expect(requests[0].request.method).toBe('POST');
    requests[0].flush(operatorBusFixture({ status: 'INACTIVE' }));
  });

  it('handles a 403 after a previously accessible bus list', async () => {
    const setup = await configure(OperatorBusesPageComponent);
    const fixture = TestBed.createComponent(OperatorBusesPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/bus-types`).flush([operatorBusTypeFixture()]);
    setup.http
      .expectOne((request) => request.url === `${base}/operator-1/seat-layouts`)
      .flush([operatorSeatLayoutFixture()]);
    setup.http.expectOne(`${base}/operator-1/buses`).flush(
      {},
      { status: 403, statusText: 'Forbidden' }
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.buses).toEqual([]);
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('handles a 404 when a bus is no longer accessible', async () => {
    const setup = await configure(OperatorBusDetailPageComponent, { busId: 'bus-1' });
    const fixture = TestBed.createComponent(OperatorBusDetailPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/bus-types`).flush([operatorBusTypeFixture()]);
    setup.http
      .expectOne((request) => request.url === `${base}/operator-1/seat-layouts`)
      .flush([operatorSeatLayoutFixture()]);
    setup.http.expectOne(`${base}/operator-1/buses/bus-1`).flush(
      {},
      { status: 404, statusText: 'Not Found' }
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.bus).toBeNull();
    expect(pageText(fixture.nativeElement)).toContain('Resource not found');
  });

  it('handles session expiry during a previously accessible bus request', async () => {
    const setup = await configure(OperatorBusDetailPageComponent, { busId: 'bus-1' });
    const fixture = TestBed.createComponent(OperatorBusDetailPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${base}/operator-1/bus-types`).flush([operatorBusTypeFixture()]);
    setup.http
      .expectOne((request) => request.url === `${base}/operator-1/seat-layouts`)
      .flush([operatorSeatLayoutFixture()]);
    setup.http.expectOne(`${base}/operator-1/buses/bus-1`).flush(
      {},
      { status: 401, statusText: 'Unauthorized' }
    );
    fixture.detectChanges();

    expect(setup.clearSession).toHaveBeenCalled();
    expect(setup.navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { returnUrl: '/' }
    });
    expect(fixture.componentInstance.bus).toBeNull();
    expect(pageText(fixture.nativeElement)).toContain('Your session has expired');
    expect(pageText(fixture.nativeElement)).not.toContain('Try again');
  });

  it('clears the previous operator list before loading a newly selected operator', async () => {
    const setup = await configure(OperatorBusesPageComponent);
    const fixture = TestBed.createComponent(OperatorBusesPageComponent);
    fixture.detectChanges();
    flushBusCollection(setup.http, 'operator-1', [operatorBusFixture()]);
    expect(fixture.componentInstance.buses[0].registrationNumber).toBe('AP31AB1234');

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.buses).toEqual([]);

    flushBusCollection(setup.http, 'operator-2', [
      operatorBusFixture({
        id: 'bus-2',
        operatorId: 'operator-2',
        registrationNumber: 'TS09XY9876'
      })
    ]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('TS09XY9876');
    expect(text).not.toContain('AP31AB1234');
  });

  it('clears stale bus detail before loading a newly selected operator', async () => {
    const setup = await configure(OperatorBusDetailPageComponent, { busId: 'bus-1' });
    const fixture = TestBed.createComponent(OperatorBusDetailPageComponent);
    fixture.detectChanges();
    flushBusDetail(setup.http, 'operator-1');
    expect(fixture.componentInstance.bus?.registrationNumber).toBe('AP31AB1234');

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.bus).toBeNull();

    setup.http
      .expectOne(`${base}/operator-2/buses/bus-1`)
      .flush(
        operatorBusFixture({
          operatorId: 'operator-2',
          registrationNumber: 'TS09XY9876',
          displayName: 'Inland Sleeper'
        })
      );
    setup.http.expectOne(`${base}/operator-2/bus-types`).flush([operatorBusTypeFixture()]);
    setup.http
      .expectOne((request) => request.url === `${base}/operator-2/seat-layouts`)
      .flush([operatorSeatLayoutFixture({ operatorId: 'operator-2' })]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('TS09XY9876');
    expect(text).not.toContain('AP31AB1234');
    expect(fixture.componentInstance.bus?.operatorId).toBe('operator-2');
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
      'bookings/:bookingId',
      'profile',
      'trips/:tripId/seats',
      'checkout/:holdId/passengers',
      'checkout/:holdId/review',
      'payment/:bookingId'
    ]);
  });

  async function configure(
    component: Type<unknown>,
    params: Record<string, string> = {},
    canManage = true
  ): Promise<{
    http: HttpTestingController;
    selectedOperatorId: WritableSignal<string | null>;
    clearSession: jasmine.Spy<() => void>;
    navigate: Router['navigate'];
  }> {
    const selectedOperatorId = signal<string | null>('operator-1');
    const canManageOperator = signal(canManage);
    const clearSession = jasmine.createSpy('clearSession');

    await TestBed.configureTestingModule({
      imports: [component],
      providers: [
        provideRouter([]),
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
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap(params),
              queryParamMap: convertToParamMap({})
            }
          }
        }
      ]
    }).compileComponents();

    const http = TestBed.inject(HttpTestingController);
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate').and.resolveTo(true);
    return { http, selectedOperatorId, clearSession, navigate };
  }

  function flushReferences(http: HttpTestingController, operatorId: string): void {
    http
      .expectOne(`${base}/${operatorId}/bus-types`)
      .flush([operatorBusTypeFixture()]);
    const layouts = http.expectOne(
      (request) => request.url === `${base}/${operatorId}/seat-layouts`
    );
    expect(layouts.request.params.get('status')).toBe('PUBLISHED');
    layouts.flush([operatorSeatLayoutFixture({ operatorId })]);
  }

  function flushBusCollection(
    http: HttpTestingController,
    operatorId: string,
    buses: ReturnType<typeof operatorBusFixture>[]
  ): void {
    http.expectOne(`${base}/${operatorId}/buses`).flush(buses);
    flushReferences(http, operatorId);
  }

  function flushBusDetail(
    http: HttpTestingController,
    operatorId: string,
    layouts = [operatorSeatLayoutFixture({ operatorId })]
  ): void {
    http
      .expectOne(`${base}/${operatorId}/buses/bus-1`)
      .flush(operatorBusFixture({ operatorId }));
    http
      .expectOne(`${base}/${operatorId}/bus-types`)
      .flush([operatorBusTypeFixture()]);
    const layoutRequest = http.expectOne(
      (request) => request.url === `${base}/${operatorId}/seat-layouts`
    );
    expect(layoutRequest.request.params.get('status')).toBe('PUBLISHED');
    layoutRequest.flush(layouts);
  }

  function pageText(element: HTMLElement): string {
    return element.textContent ?? '';
  }
});
