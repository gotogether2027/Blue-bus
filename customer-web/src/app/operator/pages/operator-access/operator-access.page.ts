import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { OperatorRetryButtonComponent } from '../../components/operator-retry-button';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { operatorRoleLabel, operatorStatusTone } from '../../components/operator-status';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-access-page',
  imports: [RouterLink, EmptyStateComponent, OperatorRetryButtonComponent, StatusBadgeComponent],
  templateUrl: './operator-access.page.html'
})
export class OperatorAccessPageComponent implements OnInit {
  readonly auth = inject(AuthService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  loading = true;
  error: OperatorPageError | null = null;
  accessDenied = false;
  readonly roleLabel = operatorRoleLabel;
  readonly statusTone = operatorStatusTone;

  ngOnInit(): void {
    this.accessDenied = this.route.snapshot.queryParamMap.get('accessDenied') === 'true';
    if (this.route.snapshot.queryParamMap.get('membershipsUnavailable') === 'true') {
      this.loading = false;
      this.error = {
        kind: 'server',
        title: 'Operator access is unavailable',
        message: 'BLUE BUS could not verify your operator memberships. Please try again.'
      };
      return;
    }
    this.loadMemberships();
  }

  loadMemberships(): void {
    this.loading = true;
    this.error = null;
    this.context.loadMemberships(true).subscribe({
      next: (memberships) => {
        this.loading = false;
        if (memberships.length === 1 && !this.accessDenied) {
          void this.router.navigate(['/operator', memberships[0].operatorId], {
            replaceUrl: true
          });
        }
      },
      error: (error: unknown) => {
        this.loading = false;
        this.error = this.errors.handle(error, '/operator');
      }
    });
  }

  openOperator(operatorId: string): void {
    if (this.context.selectOperator(operatorId)) {
      void this.router.navigate(['/operator', operatorId]);
    }
  }

  logout(): void {
    this.auth.logout().subscribe(() => {
      this.context.reset();
      void this.router.navigate(['/']);
    });
  }
}
