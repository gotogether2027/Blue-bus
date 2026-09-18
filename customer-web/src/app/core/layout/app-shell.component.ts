import { Component, effect, inject } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { OperatorContextService } from '../../operator/services/operator-context.service';
import { AuthService } from '../auth/auth.service';
import { LoadingService } from '../http/loading.service';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app-shell.component.html'
})
export class AppShellComponent {
  readonly auth = inject(AuthService);
  readonly loading = inject(LoadingService);
  readonly operatorContext = inject(OperatorContextService);
  private readonly router = inject(Router);
  menuOpen = false;

  constructor() {
    effect((onCleanup) => {
      const authenticatedUserId = this.auth.currentUser()?.userId ?? null;
      this.operatorContext.reset();
      if (!authenticatedUserId) {
        return;
      }
      const subscription = this.operatorContext.loadMemberships(true).subscribe({
        error: () => this.operatorContext.reset()
      });
      onCleanup(() => subscription.unsubscribe());
    });
    this.router.events.pipe(filter((event) => event instanceof NavigationEnd)).subscribe(() => {
      this.menuOpen = false;
    });
  }

  logout(): void {
    this.auth.logout().subscribe(() => {
      void this.router.navigate(['/']);
    });
  }
}
