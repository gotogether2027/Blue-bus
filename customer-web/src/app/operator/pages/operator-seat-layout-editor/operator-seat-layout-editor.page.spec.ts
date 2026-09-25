import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import { OperatorSeatLayoutEditorPageComponent } from './operator-seat-layout-editor.page';

describe('OperatorSeatLayoutEditorPageComponent', () => {
  let fixture: ComponentFixture<OperatorSeatLayoutEditorPageComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [OperatorSeatLayoutEditorPageComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: OperatorApiService, useValue: {} },
        {
          provide: OperatorContextService,
          useValue: {
            selectedOperatorId: signal('operator-1').asReadonly(),
            canManageOperator: () => true
          }
        }
      ]
    });
    fixture = TestBed.createComponent(OperatorSeatLayoutEditorPageComponent);
    fixture.detectChanges();
  });

  it('applies a template and updates the live preview summary', () => {
    const page = fixture.componentInstance;
    expect(fixture.nativeElement.textContent).toContain('Live preview');
    expect(page.summary().total).toBe(32);
    expect(fixture.nativeElement.querySelectorAll('app-seat-layout-canvas').length).toBe(2);
    const preview = fixture.nativeElement.querySelectorAll('app-seat-layout-canvas')[1] as HTMLElement;
    expect(preview.querySelector('button')?.hasAttribute('disabled')).toBeTrue();

    page.applyTemplate('sleeper-2-1');
    fixture.detectChanges();
    expect(page.summary().sleepers).toBe(page.summary().total);
    expect(page.draft.seats.every((seat) => seat.orientation === 'VERTICAL')).toBeTrue();
    expect(page.draft.seats.every((seat) => seat.spanRows === 2)).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain(String(page.summary().total));
  });

  it('places a custom seat and shows it in the preview', () => {
    const page = fixture.componentInstance;
    page.applyTemplate('custom');
    page.draft = { ...page.draft, name: 'Custom coach' };
    page.tool = 'SEATER';
    page.selectCell({ deck: 1, row: 2, column: 1 });
    fixture.detectChanges();
    expect(page.summary().total).toBe(1);
    expect(page.summary().seaters).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('01A');
    expect(page.problems()).toEqual([]);
  });
});
