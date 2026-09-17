import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { LocationsService } from './locations.service';
import { locationFixture } from '../../../testing/fixtures';
import { environment } from '../../../environments/environment';

describe('LocationsService', () => {
  let service: LocationsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(LocationsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads customer locations from GET /locations', () => {
    const rows = [locationFixture('1', 'Visakhapatnam')];
    service.list().subscribe((result) => expect(result).toEqual(rows));
    const req = http.expectOne(`${environment.apiBaseUrl}/locations`);
    expect(req.request.params.keys().length).toBe(0);
    req.flush(rows);
  });

  it('forwards optional state and city filters', () => {
    service.list({ state: 'Telangana', city: 'Hyderabad' }).subscribe();
    const req = http.expectOne(
      (request) => request.url === `${environment.apiBaseUrl}/locations`
    );
    expect(req.request.params.get('state')).toBe('Telangana');
    expect(req.request.params.get('city')).toBe('Hyderabad');
    req.flush([]);
  });
});
