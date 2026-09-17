import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-empty-state',
  template: `
    <div class="empty">
      <h2>{{ title }}</h2>
      <p>{{ message }}</p>
      <ng-content />
    </div>
  `
})
export class EmptyStateComponent {
  @Input({ required: true }) title = '';
  @Input() message = '';
}
