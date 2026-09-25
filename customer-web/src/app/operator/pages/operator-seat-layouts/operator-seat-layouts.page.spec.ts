import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';
import { operatorSeatLayoutFixture } from '../../../../testing/operator-fixtures';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import { OperatorSeatLayoutsPageComponent } from './operator-seat-layouts.page';

describe('OperatorSeatLayoutsPageComponent', () => {
  let fixture: ComponentFixture<OperatorSeatLayoutsPageComponent>;

  function setup(canManage: boolean, layouts = [operatorSeatLayoutFixture(), operatorSeatLayoutFixture({
    id: 'layout-2',
    name: 'City Seater',
    status: 'DRAFT',
    layoutType: 'SEATER'
  })]) {
    TestBed.configureTestingModule({
      imports: [OperatorSeatLayoutsPageComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: OperatorApiService,
          useValue: { listSeatLayouts: () => of(layouts) }
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
    fixture = TestBed.createComponent(OperatorSeatLayoutsPageComponent);
    fixture.detectChanges();
  }

  it('lists layouts and filters by search, status, and type', () => {
    setup(true);
    const page = fixture.componentInstance;
    expect(fixture.nativeElement.textContent).toContain('Sleeper 2+1');
    page.searchQuery = 'city';
    fixture.detectChanges();
    expect(page.filtered.map((layout) => layout.id)).toEqual(['layout-2']);
    page.searchQuery = '';
    page.statusFilter = 'DRAFT';
    expect(page.filtered.every((layout) => layout.status === 'DRAFT')).toBeTrue();
    page.statusFilter = 'ALL';
    page.typeFilter = 'SEATER';
    expect(page.filtered.map((layout) => layout.layoutType)).toEqual(['SEATER']);
  });

  it('shows mutation actions only for an administrator', () => {
    setup(true);
    expect(fixture.nativeElement.textContent).toContain('Duplicate');
    expect(fixture.nativeElement.textContent).toContain('Create seat layout');
    TestBed.resetTestingModule();
    setup(false);
    expect(fixture.nativeElement.textContent).not.toContain('Duplicate');
    expect(fixture.nativeElement.textContent).toContain('View');
  });

  it('publishes, archives, and duplicates layouts for an administrator', () => {
    const published = operatorSeatLayoutFixture();
    const draft = operatorSeatLayoutFixture({
      id: 'layout-2',
      name: 'City Seater',
      status: 'DRAFT',
      layoutType: 'SEATER'
    });
    const api = {
      listSeatLayouts: jasmine.createSpy('listSeatLayouts').and.returnValue(of([published, draft])),
      publishSeatLayout: jasmine.createSpy('publishSeatLayout').and.returnValue(of({ ...draft, status: 'PUBLISHED' })),
      archiveSeatLayout: jasmine.createSpy('archiveSeatLayout').and.returnValue(of({ ...published, status: 'ARCHIVED' })),
      duplicateSeatLayout: jasmine.createSpy('duplicateSeatLayout').and.returnValue(of({ ...draft, id: 'layout-copy' }))
    };
    TestBed.configureTestingModule({
      imports: [OperatorSeatLayoutsPageComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: OperatorApiService, useValue: api },
        {
          provide: OperatorContextService,
          useValue: {
            selectedOperatorId: signal('operator-1').asReadonly(),
            canManageOperator: () => true
          }
        }
      ]
    });
    fixture = TestBed.createComponent(OperatorSeatLayoutsPageComponent);
    fixture.detectChanges();
    spyOn(window, 'confirm').and.returnValue(true);

    const publish = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    buttons.find((button) => button.textContent?.includes('Publish'))?.click();
    expect(api.publishSeatLayout).toHaveBeenCalledWith('operator-1', 'layout-2');

    buttons.find((button) => button.textContent?.includes('Archive'))?.click();
    expect(api.archiveSeatLayout).toHaveBeenCalledWith('operator-1', published.id);

    buttons.find((button) => button.textContent?.includes('Duplicate'))?.click();
    expect(api.duplicateSeatLayout).toHaveBeenCalled();
    expect(publish).toBeTruthy();
  });
});
