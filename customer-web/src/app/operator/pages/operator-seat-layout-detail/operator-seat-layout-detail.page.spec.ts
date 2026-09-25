import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';
import { operatorSeatLayoutFixture } from '../../../../testing/operator-fixtures';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import { OperatorSeatLayoutDetailPageComponent } from './operator-seat-layout-detail.page';

describe('OperatorSeatLayoutDetailPageComponent', () => {
  let fixture: ComponentFixture<OperatorSeatLayoutDetailPageComponent>;

  function setup(canManage: boolean, status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' = 'DRAFT') {
    const layout = operatorSeatLayoutFixture({
      status,
      seats: [
        {
          id: 'seat-1',
          seatNumber: '01A',
          seatType: 'SEATER',
          deckNumber: 1,
          rowNumber: 1,
          columnNumber: 1,
          sellable: true,
          orientation: 'FORWARD',
          spanRows: 1,
          spanColumns: 1
        }
      ]
    });
    const api = {
      getSeatLayout: () => of(layout),
      publishSeatLayout: jasmine.createSpy('publish').and.returnValue(of({ ...layout, status: 'PUBLISHED' })),
      archiveSeatLayout: jasmine.createSpy('archive').and.returnValue(of({ ...layout, status: 'ARCHIVED' })),
      duplicateSeatLayout: jasmine.createSpy('duplicate').and.returnValue(of({ ...layout, id: 'layout-copy', status: 'DRAFT' }))
    };
    TestBed.configureTestingModule({
      imports: [OperatorSeatLayoutDetailPageComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: OperatorApiService, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ layoutId: layout.id }) } }
        },
        {
          provide: OperatorContextService,
          useValue: {
            selectedOperatorId: signal('operator-1').asReadonly(),
            canManageOperator: () => canManage
          }
        }
      ]
    });
    fixture = TestBed.createComponent(OperatorSeatLayoutDetailPageComponent);
    fixture.detectChanges();
    return api;
  }

  it('lets an administrator publish, archive, and duplicate a draft', () => {
    const api = setup(true, 'DRAFT');
    expect(fixture.nativeElement.textContent).toContain('01A');
    expect(fixture.nativeElement.textContent).toContain('Publish');
    spyOn(window, 'confirm').and.returnValue(true);
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    buttons.find((button) => button.textContent?.includes('Publish'))?.click();
    expect(api.publishSeatLayout).toHaveBeenCalledWith('operator-1', 'layout-1');
    buttons.find((button) => button.textContent?.includes('Archive'))?.click();
    expect(api.archiveSeatLayout).toHaveBeenCalledWith('operator-1', 'layout-1');
    buttons.find((button) => button.textContent?.includes('Duplicate'))?.click();
    expect(api.duplicateSeatLayout).toHaveBeenCalledWith('operator-1', 'layout-1');
  });

  it('keeps a published layout view-only for staff', () => {
    setup(false, 'PUBLISHED');
    expect(fixture.nativeElement.textContent).toContain('This layout is view-only.');
    expect(fixture.nativeElement.textContent).not.toContain('Publish');
    expect(fixture.nativeElement.textContent).not.toContain('Duplicate');
    expect(fixture.nativeElement.querySelector('app-seat-layout-canvas button')?.hasAttribute('disabled')).toBeTrue();
  });
});
