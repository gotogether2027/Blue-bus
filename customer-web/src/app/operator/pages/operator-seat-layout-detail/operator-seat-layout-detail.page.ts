import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { OperatorSeatLayout } from '../../models/operator.models';
import { SeatLayoutCanvasComponent } from '../../seat-layout/seat-layout-canvas.component';
import { draftFromLayout, summaryOf } from '../../seat-layout/seat-layout-draft';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import { OperatorErrorService, OperatorPageError } from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-seat-layout-detail-page',
  imports: [RouterLink, StatusBadgeComponent, SeatLayoutCanvasComponent],
  templateUrl: './operator-seat-layout-detail.page.html'
})
export class OperatorSeatLayoutDetailPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  loading = true;
  error: OperatorPageError | null = null;
  actionError = '';
  layout: OperatorSeatLayout | null = null;
  deck = 1;

  get operatorId(): string {
    return this.context.selectedOperatorId() ?? '';
  }

  get draft() {
    return this.layout ? draftFromLayout(this.layout) : null;
  }

  get summary() {
    return this.draft ? summaryOf(this.draft) : null;
  }

  ngOnInit(): void {
    const layoutId = this.route.snapshot.paramMap.get('layoutId');
    if (!layoutId) {
      return;
    }
    this.api.getSeatLayout(this.operatorId, layoutId).subscribe({
      next: (layout) => {
        this.layout = layout;
        this.loading = false;
      },
      error: (error: unknown) => {
        this.error = this.errors.handle(error);
        this.loading = false;
      }
    });
  }

  publish(): void {
    if (!this.layout || !this.context.canManageOperator()) {
      return;
    }
    this.api.publishSeatLayout(this.operatorId, this.layout.id).subscribe({
      next: (layout) => (this.layout = layout),
      error: (error: unknown) => (this.actionError = this.errors.handle(error).message)
    });
  }

  archive(): void {
    if (!this.layout || !this.context.canManageOperator()) {
      return;
    }
    if (!window.confirm(`Archive ${this.layout.name}?`)) {
      return;
    }
    this.api.archiveSeatLayout(this.operatorId, this.layout.id).subscribe({
      next: (layout) => (this.layout = layout),
      error: (error: unknown) => (this.actionError = this.errors.handle(error).message)
    });
  }

  duplicate(): void {
    if (!this.layout || !this.context.canManageOperator()) {
      return;
    }
    this.api.duplicateSeatLayout(this.operatorId, this.layout.id).subscribe({
      next: (copy) => {
        void this.router.navigate(['/operator', this.operatorId, 'seat-layouts', copy.id, 'edit']);
      },
      error: (error: unknown) => (this.actionError = this.errors.handle(error).message)
    });
  }
}
