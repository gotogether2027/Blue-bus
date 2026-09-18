import { Component, EventEmitter, Input, Output } from '@angular/core';
import {
  OperatorPageError,
  canRetryOperatorLoad
} from '../services/operator-error.service';

@Component({
  selector: 'app-operator-retry-button',
  template: `
    @if (canRetry(error)) {
      <button type="button" class="btn btn-primary" (click)="retry.emit()">Try again</button>
    }
  `
})
export class OperatorRetryButtonComponent {
  @Input({ required: true }) error!: OperatorPageError;
  @Output() retry = new EventEmitter<void>();

  readonly canRetry = canRetryOperatorLoad;
}
