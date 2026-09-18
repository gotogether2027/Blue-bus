import { ComponentFixture, TestBed } from '@angular/core/testing';
import { OperatorPageError } from '../services/operator-error.service';
import { OperatorRetryButtonComponent } from './operator-retry-button';

describe('OperatorRetryButtonComponent', () => {
  it('offers retry for transient GET failures', async () => {
    const fixture = await render({
      kind: 'server',
      title: 'Operator data is unavailable',
      message: 'BLUE BUS could not load this operator data. Please try again.'
    });
    const retries: number[] = [];
    fixture.componentInstance.retry.subscribe(() => retries.push(1));

    const button = queryButton(fixture);
    expect(button).not.toBeNull();
    expect(button?.textContent?.trim()).toBe('Try again');
    button?.click();
    expect(retries.length).toBe(1);
  });

  it('hides retry for authentication, authorization, and conflict errors', async () => {
    for (const kind of ['unauthorized', 'forbidden', 'conflict'] as const) {
      const fixture = await render({ kind, title: kind, message: kind });
      expect(queryButton(fixture)).toBeNull();
    }
  });
});

async function render(error: OperatorPageError): Promise<ComponentFixture<OperatorRetryButtonComponent>> {
  TestBed.resetTestingModule();
  await TestBed.configureTestingModule({
    imports: [OperatorRetryButtonComponent]
  }).compileComponents();
  const fixture = TestBed.createComponent(OperatorRetryButtonComponent);
  fixture.componentInstance.error = error;
  fixture.detectChanges();
  return fixture;
}

function queryButton(fixture: ComponentFixture<OperatorRetryButtonComponent>): HTMLButtonElement | null {
  return (fixture.nativeElement as HTMLElement).querySelector('button');
}
