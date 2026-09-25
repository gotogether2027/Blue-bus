import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { OperatorRetryButtonComponent } from '../../components/operator-retry-button';
import {
  OperatorSeatLayout,
  OperatorSeatLayoutStatus,
  OperatorSeatLayoutType
} from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import { OperatorErrorService, OperatorPageError } from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-seat-layouts-page',
  imports: [FormsModule, RouterLink, EmptyStateComponent, StatusBadgeComponent, OperatorRetryButtonComponent],
  templateUrl: './operator-seat-layouts.page.html'
})
export class OperatorSeatLayoutsPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  loading = true;
  error: OperatorPageError | null = null;
  actionError = '';
  layouts: OperatorSeatLayout[] = [];
  searchQuery = '';
  statusFilter: OperatorSeatLayoutStatus | 'ALL' = 'ALL';
  typeFilter: OperatorSeatLayoutType | 'ALL' = 'ALL';
  readonly statuses: OperatorSeatLayoutStatus[] = ['DRAFT', 'PUBLISHED', 'ARCHIVED'];
  readonly layoutTypes: OperatorSeatLayoutType[] = ['SEATER', 'SLEEPER', 'SEATER_SLEEPER', 'CUSTOM'];
  readonly writeAccessDenied = this.route.snapshot.queryParamMap.get('writeAccessDenied') === 'true';

  get operatorId(): string {
    return this.context.selectedOperatorId() ?? '';
  }

  get filtered(): OperatorSeatLayout[] {
    const query = this.searchQuery.trim().toLocaleLowerCase();
    return this.layouts.filter((layout) => {
      if (this.statusFilter !== 'ALL' && layout.status !== this.statusFilter) {
        return false;
      }
      if (this.typeFilter !== 'ALL' && layout.layoutType !== this.typeFilter) {
        return false;
      }
      if (!query) {
        return true;
      }
      return layout.name.toLocaleLowerCase().includes(query);
    });
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const operatorId = this.operatorId;
    if (!operatorId) {
      return;
    }
    this.loading = true;
    this.error = null;
    this.api.listSeatLayouts(operatorId).subscribe({
      next: (layouts) => {
        this.layouts = layouts;
        this.loading = false;
      },
      error: (error: unknown) => {
        this.error = this.errors.handle(error);
        this.loading = false;
      }
    });
  }

  publish(layout: OperatorSeatLayout): void {
    if (!this.context.canManageOperator() || layout.status !== 'DRAFT') {
      return;
    }
    this.api.publishSeatLayout(this.operatorId, layout.id).subscribe({
      next: () => this.load(),
      error: (error: unknown) => {
        this.actionError = this.errors.handle(error).message;
      }
    });
  }

  archive(layout: OperatorSeatLayout): void {
    if (!this.context.canManageOperator() || layout.status === 'ARCHIVED') {
      return;
    }
    if (!window.confirm(`Archive ${layout.name}? It cannot be assigned to new buses.`)) {
      return;
    }
    this.api.archiveSeatLayout(this.operatorId, layout.id).subscribe({
      next: () => this.load(),
      error: (error: unknown) => {
        this.actionError = this.errors.handle(error).message;
      }
    });
  }

  duplicate(layout: OperatorSeatLayout): void {
    if (!this.context.canManageOperator()) {
      return;
    }
    this.api.duplicateSeatLayout(this.operatorId, layout.id).subscribe({
      next: (copy) => {
        void this.router.navigate(['/operator', this.operatorId, 'seat-layouts', copy.id, 'edit']);
      },
      error: (error: unknown) => {
        this.actionError = this.errors.handle(error).message;
      }
    });
  }

  updatedLabel(layout: OperatorSeatLayout): string {
    if (!layout.updatedAt) {
      return '—';
    }
    return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(layout.updatedAt)
    );
  }
}
