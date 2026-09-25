import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SeatLayoutCanvasComponent } from './seat-layout-canvas.component';
import { SEAT_LAYOUT_TEMPLATES } from './seat-layout-templates';

describe('SeatLayoutCanvasComponent', () => {
  let fixture: ComponentFixture<SeatLayoutCanvasComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [SeatLayoutCanvasComponent] });
    fixture = TestBed.createComponent(SeatLayoutCanvasComponent);
  });

  it('renders seater numbers and sleeper berths', () => {
    fixture.componentRef.setInput('draft', SEAT_LAYOUT_TEMPLATES[0].build());
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('01A');
    expect(text).toContain('DRIVER');
    expect(fixture.nativeElement.querySelector('.seat.seater')).not.toBeNull();

    fixture.componentRef.setInput('draft', SEAT_LAYOUT_TEMPLATES[4].build());
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.seat.sleeper')).not.toBeNull();
    const berth = fixture.nativeElement.querySelector('.seat.sleeper') as HTMLElement;
    expect(berth.style.gridRow).toContain('span 2');
  });

  it('renders a second deck independently', () => {
    const draft = SEAT_LAYOUT_TEMPLATES.find((template) => template.id === 'multi-sleeper')!.build();
    fixture.componentRef.setInput('draft', draft);
    fixture.componentRef.setInput('deck', 2);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('UPPER');
    expect(fixture.nativeElement.getAttribute('aria-label') || fixture.nativeElement.textContent).toBeTruthy();
  });
});
