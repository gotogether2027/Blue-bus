import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EmptyStateComponent } from '../../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { operatorStatusTone } from '../../components/operator-status';
import { OperatorBus } from '../../models/operator.models';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import {
  OperatorErrorService,
  OperatorPageError
} from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-buses-page',
  imports: [RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './operator-buses.page.html'
})
export class OperatorBusesPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);

  loading = true;
  error: OperatorPageError | null = null;
  buses: OperatorBus[] = [];
  readonly statusTone = operatorStatusTone;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    if (!operatorId) {
      this.showAccessDenied();
      return;
    }
    this.loading = true;
    this.error = null;
    this.api.listBuses(operatorId).subscribe({
      next: (buses) => {
        this.buses = buses;
        this.loading = false;
      },
      error: (error: unknown) => {
        this.loading = false;
        this.error = this.errors.handle(error);
      }
    });
  }

  private showAccessDenied(): void {
    this.loading = false;
    this.error = {
      kind: 'forbidden',
      title: 'Operator access denied',
      message: "You don't have access to this operator."
    };
  }
}
