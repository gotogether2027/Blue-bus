import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { environment } from '../../../environments/environment';
import { seatFixture } from '../../../testing/seat-fixtures';
import { SeatPageComponent } from './seats.page';

describe('SeatPageComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SeatPageComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({ tripId: 'trip-1' }),
              queryParamMap: convertToParamMap({
                originStopId: 'stop-origin',
                destinationStopId: 'stop-dest'
              })
            },
            queryParamMap: of(
              convertToParamMap({
                originStopId: 'stop-origin',
                destinationStopId: 'stop-dest'
              })
            )
          }
        }
      ]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads seat availability with originStopId and destinationStopId', () => {
    const fixture = TestBed.createComponent(SeatPageComponent);
    fixture.detectChanges();
    const req = http.expectOne((request) => request.url === `${environment.apiBaseUrl}/trips/trip-1/seat-availability`);
    expect(req.request.params.get('originStopId')).toBe('stop-origin');
    expect(req.request.params.get('destinationStopId')).toBe('stop-dest');
    req.flush({
      tripId: 'trip-1',
      originStopId: 'stop-origin',
      destinationStopId: 'stop-dest',
      originSequence: 1,
      destinationSequence: 8,
      seats: [seatFixture({ seatNumber: 'U1' })]
    });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('U1');
  });
});
