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
import { locationFixture } from '../../../testing/fixtures';
import {
  operatorRouteFixture,
  operatorRouteStopFixture,
  operatorTripFixture
} from '../../../testing/operator-fixtures';
import { routes } from '../../app.routes';
import { AuthService } from '../../core/auth/auth.service';
import { OperatorContextService } from '../services/operator-context.service';
import { OperatorRouteCreatePageComponent } from './operator-route-create/operator-route-create.page';
import { OperatorRouteDetailPageComponent } from './operator-route-detail/operator-route-detail.page';
import { OperatorRouteEditPageComponent } from './operator-route-edit/operator-route-edit.page';
import { OperatorRouteStopsPageComponent } from './operator-route-stops/operator-route-stops.page';
import { OperatorRoutesPageComponent } from './operator-routes/operator-routes.page';

describe('operator route management', () => {
  const base = `${environment.apiBaseUrl}/operator`;
  const hyd = locationFixture('location-hyd', 'Hyderabad', 'Telangana');
  const vja = locationFixture('location-vja', 'Vijayawada', 'Andhra Pradesh');

  afterEach(() => {
    const http = TestBed.inject(HttpTestingController, null, { optional: true });
    http?.verify();
  });

  it('renders an empty route list without fabricating network data', async () => {
    const setup = await configure(OperatorRoutesPageComponent);
    const fixture = TestBed.createComponent(OperatorRoutesPageComponent);
    fixture.detectChanges();
    flushRouteCollection(setup.http, 'operator-1', []);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('No routes');
    expect(text).toContain('Create the first route');
    expect(text).not.toContain('revenue');
    expect(text).not.toContain('duration');
  });

  it('loads the route list with actual operator-safe fields', async () => {
    const setup = await configure(OperatorRoutesPageComponent);
    const fixture = TestBed.createComponent(OperatorRoutesPageComponent);
    fixture.detectChanges();
    flushRouteCollection(setup.http, 'operator-1', [operatorRouteFixture()]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('HYD-VJA');
    expect(text).toContain('Hyderabad to Vijayawada');
    expect(text).toContain('Hyderabad');
    expect(text).toContain('Vijayawada');
    expect(text).toContain('ACTIVE');
    expect(text).toContain('1. Hyderabad');
    expect(text).toContain('View route');
    expect(text).toContain('Create route');
  });

  it('hides list mutation controls from operator staff', async () => {
    const setup = await configure(OperatorRoutesPageComponent, {}, false);
    const fixture = TestBed.createComponent(OperatorRoutesPageComponent);
    fixture.detectChanges();
    flushRouteCollection(setup.http, 'operator-1', [operatorRouteFixture()]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('View route');
    expect(text).toContain('read-only');
    expect(text).not.toContain('Create route');
  });

  it('loads required active locations for the create form', async () => {
    const setup = await configure(OperatorRouteCreatePageComponent);
    const fixture = TestBed.createComponent(OperatorRouteCreatePageComponent);
    fixture.detectChanges();
    flushLocations(setup.http);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Hyderabad');
    expect(text).toContain('Vijayawada');
    expect(text).toContain('Source and destination must be different');
  });

  it('blocks create when source and destination are the same', async () => {
    const setup = await configure(OperatorRouteCreatePageComponent);
    const fixture = TestBed.createComponent(OperatorRouteCreatePageComponent);
    fixture.detectChanges();
    flushLocations(setup.http);

    fixture.componentInstance.form.setValue({
      code: 'HYD-HYD',
      name: 'Loop',
      sourceLocationId: 'location-hyd',
      destinationLocationId: 'location-hyd',
      stops: []
    });
    fixture.componentInstance.submit();
    setup.http.expectNone(`${base}/operator-1/routes`);
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain('Source and destination must differ');
  });

  it('creates a route and navigates to its operator-scoped detail page', async () => {
    const setup = await configure(OperatorRouteCreatePageComponent);
    const fixture = TestBed.createComponent(OperatorRouteCreatePageComponent);
    fixture.detectChanges();
    flushLocations(setup.http);

    fixture.componentInstance.form.patchValue({
      code: ' HYD-VJA ',
      name: ' Hyderabad to Vijayawada ',
      sourceLocationId: 'location-hyd',
      destinationLocationId: 'location-vja'
    });
    fixture.componentInstance.submit();

    const request = setup.http.expectOne(`${base}/operator-1/routes`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      code: 'HYD-VJA',
      name: 'Hyderabad to Vijayawada',
      sourceLocationId: 'location-hyd',
      destinationLocationId: 'location-vja'
    });
    request.flush(operatorRouteFixture());

    expect(setup.navigate).toHaveBeenCalledWith(
      ['/operator', 'operator-1', 'routes', 'route-1'],
      { queryParams: { created: 'true' } }
    );
  });

  it('shows the backend duplicate route-code conflict without a racy pre-check', async () => {
    const setup = await configure(OperatorRouteCreatePageComponent);
    const fixture = TestBed.createComponent(OperatorRouteCreatePageComponent);
    fixture.detectChanges();
    flushLocations(setup.http);

    fixture.componentInstance.form.patchValue({
      code: 'HYD-VJA',
      name: 'Hyderabad to Vijayawada',
      sourceLocationId: 'location-hyd',
      destinationLocationId: 'location-vja'
    });
    fixture.componentInstance.submit();
    setup.http.expectOne(`${base}/operator-1/routes`).flush(
      { message: 'Route code already exists for this operator.' },
      { status: 409, statusText: 'Conflict' }
    );
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain(
      'Route code already exists for this operator.'
    );
  });

  it('loads the complete operator-safe route detail DTO with ordered stops', async () => {
    const setup = await configure(OperatorRouteDetailPageComponent, { routeId: 'route-1' });
    const fixture = TestBed.createComponent(OperatorRouteDetailPageComponent);
    fixture.detectChanges();
    flushRouteDetail(setup.http, 'operator-1');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('HYD-VJA');
    expect(text).toContain('Hyderabad to Vijayawada');
    expect(text).toContain('sequence 1');
    expect(text).toContain('sequence 2');
    expect(text).toContain('Miyapur');
    expect(text).toContain('Benz Circle');
    expect(text).toContain('route-1');
    expect(text).toContain('operator-1');
  });

  it('shows mutation controls to admins', async () => {
    const setup = await configure(OperatorRouteDetailPageComponent, { routeId: 'route-1' });
    const fixture = TestBed.createComponent(OperatorRouteDetailPageComponent);
    fixture.detectChanges();
    flushRouteDetail(setup.http, 'operator-1');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Edit route');
    expect(text).toContain('Manage stops');
    expect(text).toContain('Deactivate route');
  });

  it('keeps operator staff read-only', async () => {
    const setup = await configure(
      OperatorRouteDetailPageComponent,
      { routeId: 'route-1' },
      false
    );
    const fixture = TestBed.createComponent(OperatorRouteDetailPageComponent);
    fixture.detectChanges();
    flushRouteDetail(setup.http, 'operator-1');
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('OPERATOR_STAFF membership provides read-only route access');
    expect(text).not.toContain('Edit route');
    expect(text).not.toContain('Deactivate route');
    expect(text).not.toContain('Manage stops');
  });

  it('edits only mutable metadata and never renders the immutable code input', async () => {
    const setup = await configure(OperatorRouteEditPageComponent, { routeId: 'route-1' });
    const fixture = TestBed.createComponent(OperatorRouteEditPageComponent);
    fixture.detectChanges();
    flushRouteDetail(setup.http, 'operator-1');
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('input[formControlName="code"]')).toBeNull();
    expect(pageText(element)).toContain('HYD-VJA');

    fixture.componentInstance.form.controls.name.setValue('Night Service');
    fixture.componentInstance.submit();
    const request = setup.http.expectOne(`${base}/operator-1/routes/route-1`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ name: 'Night Service' });
    request.flush(operatorRouteFixture({ name: 'Night Service' }));

    expect(setup.navigate).toHaveBeenCalledWith(
      ['/operator', 'operator-1', 'routes', 'route-1'],
      { queryParams: { updated: 'true' } }
    );
  });

  it('explains structural restrictions and does not send doomed endpoint changes', async () => {
    const setup = await configure(OperatorRouteEditPageComponent, { routeId: 'route-1' });
    const fixture = TestBed.createComponent(OperatorRouteEditPageComponent);
    fixture.detectChanges();
    flushRouteDetail(setup.http, 'operator-1', [operatorTripFixture()]);
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain('already has trips');
    expect(fixture.componentInstance.form.controls.sourceLocationId.disabled).toBeTrue();

    fixture.componentInstance.form.controls.sourceLocationId.enable();
    fixture.componentInstance.form.controls.destinationLocationId.enable();
    fixture.componentInstance.form.patchValue({
      sourceLocationId: 'location-vja',
      destinationLocationId: 'location-hyd'
    });
    fixture.componentInstance.submit();
    setup.http.expectNone(`${base}/operator-1/routes/route-1`);
    fixture.detectChanges();
    expect(pageText(fixture.nativeElement)).toContain(
      'Only the route name can be changed while trips exist'
    );
  });

  it('shows a backend structural 409 when stop mutation is rejected', async () => {
    const setup = await configure(OperatorRouteStopsPageComponent, { routeId: 'route-1' });
    const fixture = TestBed.createComponent(OperatorRouteStopsPageComponent);
    fixture.detectChanges();
    flushRouteDetail(setup.http, 'operator-1');
    fixture.detectChanges();

    fixture.componentInstance.startAddStop();
    fixture.componentInstance.stopForm.setValue({
      locationId: 'location-hyd',
      sequenceNumber: 3,
      stopKind: 'INTERMEDIATE',
      arrivalOffsetMinutes: 90,
      departureOffsetMinutes: 100,
      distanceKm: 140
    });
    fixture.componentInstance.submitStop();
    setup.http.expectOne(`${base}/operator-1/routes/route-1/stops`).flush(
      { message: 'Route stops cannot be changed while trips exist for this route.' },
      { status: 409, statusText: 'Conflict' }
    );
    fixture.detectChanges();

    expect(pageText(fixture.nativeElement)).toContain(
      'Route stops cannot be changed while trips exist for this route.'
    );
  });

  it('persists a new stop using the operator stop contract', async () => {
    const setup = await configure(OperatorRouteStopsPageComponent, { routeId: 'route-1' });
    const fixture = TestBed.createComponent(OperatorRouteStopsPageComponent);
    fixture.detectChanges();
    flushRouteDetail(setup.http, 'operator-1');

    fixture.componentInstance.startAddStop();
    fixture.componentInstance.stopForm.setValue({
      locationId: 'location-hyd',
      sequenceNumber: 3,
      stopKind: 'INTERMEDIATE',
      arrivalOffsetMinutes: 90,
      departureOffsetMinutes: 100,
      distanceKm: 140
    });
    fixture.componentInstance.submitStop();
    const request = setup.http.expectOne(`${base}/operator-1/routes/route-1/stops`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      locationId: 'location-hyd',
      sequenceNumber: 3,
      stopKind: 'INTERMEDIATE',
      arrivalOffsetMinutes: 90,
      departureOffsetMinutes: 100,
      distanceKm: 140
    });
    request.flush(
      operatorRouteStopFixture({
        id: 'route-stop-3',
        sequenceNumber: 3,
        stopKind: 'INTERMEDIATE'
      })
    );
    flushRouteDetail(setup.http, 'operator-1');
  });

  it('requires lifecycle confirmation before sending a mutation', async () => {
    const setup = await configure(OperatorRouteDetailPageComponent, { routeId: 'route-1' });
    const fixture = TestBed.createComponent(OperatorRouteDetailPageComponent);
    fixture.detectChanges();
    flushRouteDetail(setup.http, 'operator-1');

    fixture.componentInstance.requestRouteLifecycle('deactivate');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="dialog"]')).not.toBeNull();
    setup.http.expectNone(`${base}/operator-1/routes/route-1/deactivate`);

    fixture.componentInstance.cancelLifecycle();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="dialog"]')).toBeNull();
  });

  it('applies a confirmed lifecycle change and refreshes the displayed DTO', async () => {
    const setup = await configure(OperatorRouteDetailPageComponent, { routeId: 'route-1' });
    const fixture = TestBed.createComponent(OperatorRouteDetailPageComponent);
    fixture.detectChanges();
    flushRouteDetail(setup.http, 'operator-1');

    fixture.componentInstance.requestRouteLifecycle('deactivate');
    fixture.componentInstance.confirmLifecycle();
    const request = setup.http.expectOne(`${base}/operator-1/routes/route-1/deactivate`);
    expect(request.request.method).toBe('POST');
    request.flush(operatorRouteFixture({ status: 'INACTIVE' }));
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('Route deactivated successfully.');
    expect(text).toContain('INACTIVE');
  });

  it('handles a 403 after a previously accessible route list', async () => {
    const setup = await configure(OperatorRoutesPageComponent);
    const fixture = TestBed.createComponent(OperatorRoutesPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${environment.apiBaseUrl}/locations`).flush([hyd, vja]);
    setup.http.expectOne(`${base}/operator-1/routes`).flush(
      {},
      { status: 403, statusText: 'Forbidden' }
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.routes).toEqual([]);
    expect(pageText(fixture.nativeElement)).toContain("You don't have access to this operator.");
  });

  it('handles a 404 when a route is no longer accessible', async () => {
    const setup = await configure(OperatorRouteDetailPageComponent, { routeId: 'route-1' });
    const fixture = TestBed.createComponent(OperatorRouteDetailPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${environment.apiBaseUrl}/locations`).flush([hyd, vja]);
    setup.http.expectOne(`${base}/operator-1/trips`).flush([]);
    setup.http.expectOne(`${base}/operator-1/routes/route-1`).flush(
      {},
      { status: 404, statusText: 'Not Found' }
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.routeDetail).toBeNull();
    expect(pageText(fixture.nativeElement)).toContain('Resource not found');
  });

  it('handles session expiry during a previously accessible route request', async () => {
    const setup = await configure(OperatorRouteDetailPageComponent, { routeId: 'route-1' });
    const fixture = TestBed.createComponent(OperatorRouteDetailPageComponent);
    fixture.detectChanges();
    setup.http.expectOne(`${environment.apiBaseUrl}/locations`).flush([hyd, vja]);
    setup.http.expectOne(`${base}/operator-1/trips`).flush([]);
    setup.http.expectOne(`${base}/operator-1/routes/route-1`).flush(
      {},
      { status: 401, statusText: 'Unauthorized' }
    );
    fixture.detectChanges();

    expect(setup.clearSession).toHaveBeenCalled();
    expect(setup.navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { returnUrl: '/' }
    });
    expect(fixture.componentInstance.routeDetail).toBeNull();
    expect(pageText(fixture.nativeElement)).toContain('Your session has expired');
  });

  it('clears stale route list before loading a newly selected operator', async () => {
    const setup = await configure(OperatorRoutesPageComponent);
    const fixture = TestBed.createComponent(OperatorRoutesPageComponent);
    fixture.detectChanges();
    flushRouteCollection(setup.http, 'operator-1', [operatorRouteFixture()]);
    expect(fixture.componentInstance.routes[0].code).toBe('HYD-VJA');

    setup.selectedOperatorId.set('operator-2');
    fixture.componentInstance.load();
    expect(fixture.componentInstance.routes).toEqual([]);

    flushRouteCollection(setup.http, 'operator-2', [
      operatorRouteFixture({
        id: 'route-2',
        operatorId: 'operator-2',
        code: 'VJA-HYD',
        name: 'Return Service'
      })
    ]);
    fixture.detectChanges();

    const text = pageText(fixture.nativeElement);
    expect(text).toContain('VJA-HYD');
    expect(text).not.toContain('HYD-VJA');
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

  function flushLocations(http: HttpTestingController): void {
    http.expectOne(`${environment.apiBaseUrl}/locations`).flush([hyd, vja]);
  }

  function flushRouteCollection(
    http: HttpTestingController,
    operatorId: string,
    routes: ReturnType<typeof operatorRouteFixture>[]
  ): void {
    http.expectOne(`${base}/${operatorId}/routes`).flush(routes);
    flushLocations(http);
  }

  function flushRouteDetail(
    http: HttpTestingController,
    operatorId: string,
    trips: ReturnType<typeof operatorTripFixture>[] = []
  ): void {
    http
      .expectOne(`${base}/${operatorId}/routes/route-1`)
      .flush(operatorRouteFixture({ operatorId }));
    http.expectOne(`${base}/${operatorId}/trips`).flush(trips);
    flushLocations(http);
  }

  function pageText(element: HTMLElement): string {
    return element.textContent ?? '';
  }
});
