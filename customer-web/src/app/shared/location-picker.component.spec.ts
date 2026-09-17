import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { environment } from '../../environments/environment';
import { locationFixture } from '../../testing/fixtures';
import { LocationPickerComponent } from './location-picker.component';

describe('LocationPickerComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LocationPickerComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads ACTIVE customer locations on init', () => {
    const fixture = TestBed.createComponent(LocationPickerComponent);
    fixture.detectChanges();
    const req = http.expectOne(`${environment.apiBaseUrl}/locations`);
    expect(req.request.method).toBe('GET');
    req.flush([locationFixture('1', 'Vijayawada'), locationFixture('2', 'Hyderabad', 'Telangana')]);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.open = true;
    component.onQuery('hyd');
    expect(component.filtered.map((row) => row.city)).toEqual(['Hyderabad']);
  });

  it('shows an error when locations fail to load', () => {
    const fixture = TestBed.createComponent(LocationPickerComponent);
    fixture.detectChanges();
    http.expectOne(`${environment.apiBaseUrl}/locations`).flush(
      { message: 'down' },
      { status: 500, statusText: 'Server Error' }
    );
    fixture.componentInstance.open = true;
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Locations could not be loaded.');
  });
});
