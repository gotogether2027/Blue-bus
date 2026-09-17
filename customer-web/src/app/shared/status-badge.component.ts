import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-status-badge',
  template: `<span class="badge" [attr.data-tone]="tone">{{ label }}</span>`
})
export class StatusBadgeComponent {
  @Input({ required: true }) label = '';
  @Input() tone: 'neutral' | 'success' | 'warn' | 'danger' | 'info' = 'neutral';
}
