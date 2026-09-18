import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
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
  selector: 'app-operator-bus-detail-page',
  imports: [RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './operator-bus-detail.page.html'
})
export class OperatorBusDetailPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);

  loading = true;
  error: OperatorPageError | null = null;
  bus: OperatorBus | null = null;
  readonly statusTone = operatorStatusTone;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const operatorId = this.context.selectedOperatorId();
    const busId = this.route.snapshot.paramMap.get('busId');
    if (!operatorId || !busId) {
      this.loading = false;
      this.error = {
        kind: 'not-found',
        title: 'Bus not found',
        message: 'The requested bus could not be identified.'
      };
      return;
    }

    this.loading = true;
    this.error = null;
    this.api.getBus(operatorId, busId).subscribe({
      next: (bus) => {
        this.bus = bus;
        this.loading = false;
      },
      error: (error: unknown) => {
        this.loading = false;
        this.error = this.errors.handle(error);
      }
    });
  }
}
