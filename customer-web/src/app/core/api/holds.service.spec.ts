import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { environment } from '../../../environments/environment';
import { HoldsService } from './holds.service';

describe('HoldsService', () => {
  let http: HttpTestingController;
  let service: HoldsService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(HoldsService);
  });

  afterEach(() => http.verify());

  it('creates a hold with the seat-hold request contract', () => {
    service
      .create('trip-1', {
        originStopId: 'o',
        destinationStopId: 'd',
        seatInventoryIds: ['s1'],
        idempotencyKey: 'bb-hold-1'
      })
      .subscribe();
    const req = http.expectOne(`${environment.apiBaseUrl}/trips/trip-1/holds`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      originStopId: 'o',
      destinationStopId: 'd',
      seatInventoryIds: ['s1'],
      idempotencyKey: 'bb-hold-1'
    });
    req.flush({
      holdId: 'hold-1',
      tripId: 'trip-1',
      originStopId: 'o',
      destinationStopId: 'd',
      originSequence: 1,
      destinationSequence: 2,
      status: 'ACTIVE',
      expiresAt: '2099-01-01T00:00:00Z',
      seatInventoryIds: ['s1']
    });
  });

  it('surfaces expired hold status from GET /holds/{id}', () => {
    let status = '';
    service.get('hold-1').subscribe((hold) => (status = hold.status));
    http.expectOne(`${environment.apiBaseUrl}/holds/hold-1`).flush({
      holdId: 'hold-1',
      tripId: 'trip-1',
      originStopId: 'o',
      destinationStopId: 'd',
      originSequence: 1,
      destinationSequence: 2,
      status: 'EXPIRED',
      expiresAt: '2020-01-01T00:00:00Z',
      seatInventoryIds: ['s1']
    });
    expect(status).toBe('EXPIRED');
  });
});
