import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-empty-state',
  template: `
    <div class="empty" role="status">
      <h2>{{ title }}</h2>
      @if (message) {
        <p>{{ message }}</p>
      }
      <div class="empty-actions">
        <ng-content />
      </div>
    </div>
  `
})
export class EmptyStateComponent {
  @Input({ required: true }) title = '';
  @Input() message = '';
}
