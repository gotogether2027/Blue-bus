import { Component, inject } from '@angular/core';
import {
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet
} from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { LoadingService } from '../../../core/http/loading.service';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { operatorRoleLabel, operatorStatusTone } from '../../components/operator-status';
import { OperatorContextService } from '../../services/operator-context.service';

@Component({
  selector: 'app-operator-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, StatusBadgeComponent],
  templateUrl: './operator-shell.page.html'
})
export class OperatorShellComponent {
  readonly auth = inject(AuthService);
  readonly loading = inject(LoadingService);
  readonly context = inject(OperatorContextService);
  private readonly router = inject(Router);

  menuOpen = false;
  readonly roleLabel = operatorRoleLabel;
  readonly statusTone = operatorStatusTone;

  constructor() {
    this.router.events.pipe(filter((event) => event instanceof NavigationEnd)).subscribe(() => {
      this.menuOpen = false;
    });
  }

  switchOperator(event: Event): void {
    const operatorId = (event.target as HTMLSelectElement).value;
    void this.router.navigate(['/operator', operatorId]);
  }

  logout(): void {
    this.auth.logout().subscribe(() => {
      this.context.reset();
      void this.router.navigate(['/']);
    });
  }
}
