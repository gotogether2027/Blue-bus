import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { environment } from '../../../environments/environment';
import { tripSearchResultFixture } from '../../../testing/fixtures';
import { SearchPageComponent } from './search.page';

describe('SearchPageComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SearchPageComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            queryParamMap: of(
              convertToParamMap({
                originLocationId: 'loc-origin',
                destinationLocationId: 'loc-dest',
                serviceDate: '2026-09-18'
              })
            )
          }
        }
      ]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('renders operator, fare, seats, and booking CTA from the search contract', () => {
    const fixture = TestBed.createComponent(SearchPageComponent);
    fixture.detectChanges();

    const req = http.expectOne((request) => request.url === `${environment.apiBaseUrl}/search/trips`);
    expect(req.request.params.get('originLocationId')).toBe('loc-origin');
    expect(req.request.params.get('destinationLocationId')).toBe('loc-dest');
    expect(req.request.params.get('serviceDate')).toBe('2026-09-18');
    req.flush([tripSearchResultFixture()]);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Coastal Travels');
    expect(text).toContain('Coastal Sleeper');
    expect(text).toContain('12 seats left');
    expect(text).toContain('Select seats');
    expect(text).toContain('RTC Complex');
  });

  it('shows an empty state when the API returns no trips', () => {
    const fixture = TestBed.createComponent(SearchPageComponent);
    fixture.detectChanges();
    http.expectOne((request) => request.url === `${environment.apiBaseUrl}/search/trips`).flush([]);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No buses found');
  });

  it('shows an API error state', () => {
    const fixture = TestBed.createComponent(SearchPageComponent);
    fixture.detectChanges();
    http.expectOne((request) => request.url === `${environment.apiBaseUrl}/search/trips`).flush(
      { message: 'Origin and destination must be different.' },
      { status: 400, statusText: 'Bad Request' }
    );
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Origin and destination must be different.'
    );
  });
});
