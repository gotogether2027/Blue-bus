import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, finalize, of, shareReplay, tap } from 'rxjs';
import { OperatorMembership } from '../models/operator.models';
import { OperatorApiService } from './operator-api.service';

@Injectable({ providedIn: 'root' })
export class OperatorContextService {
  private readonly api = inject(OperatorApiService);
  private readonly membershipsState = signal<OperatorMembership[]>([]);
  private readonly selectedOperatorIdState = signal<string | null>(null);
  private readonly loadedState = signal(false);
  private readonly loadingState = signal(false);
  private membershipsRequest: Observable<OperatorMembership[]> | null = null;

  readonly memberships = this.membershipsState.asReadonly();
  readonly selectedOperatorId = this.selectedOperatorIdState.asReadonly();
  readonly loaded = this.loadedState.asReadonly();
  readonly loading = this.loadingState.asReadonly();
  readonly currentMembership = computed(() => {
    const selectedOperatorId = this.selectedOperatorIdState();
    return (
      this.membershipsState().find(
        (membership) => membership.operatorId === selectedOperatorId
      ) ?? null
    );
  });
  readonly canManageOperator = computed(
    () => this.currentMembership()?.role === 'OPERATOR_ADMIN'
  );
  readonly hasMultipleMemberships = computed(() => this.membershipsState().length > 1);

  loadMemberships(force = false): Observable<OperatorMembership[]> {
    if (!force && this.loadedState()) {
      return of(this.membershipsState());
    }
    if (this.membershipsRequest) {
      return this.membershipsRequest;
    }

    this.loadingState.set(true);
    const request = this.api.listMemberships().pipe(
      tap((memberships) => this.applyMemberships(memberships)),
      finalize(() => {
        this.loadingState.set(false);
        this.membershipsRequest = null;
      }),
      shareReplay({ bufferSize: 1, refCount: true })
    );
    this.membershipsRequest = request;
    return request;
  }

  selectOperator(operatorId: string): boolean {
    const isMember = this.membershipsState().some(
      (membership) => membership.operatorId === operatorId
    );
    this.selectedOperatorIdState.set(isMember ? operatorId : null);
    return isMember;
  }

  membershipFor(operatorId: string): OperatorMembership | null {
    return (
      this.membershipsState().find(
        (membership) => membership.operatorId === operatorId
      ) ?? null
    );
  }

  reset(): void {
    this.membershipsState.set([]);
    this.selectedOperatorIdState.set(null);
    this.loadedState.set(false);
    this.loadingState.set(false);
    this.membershipsRequest = null;
  }

  private applyMemberships(memberships: OperatorMembership[]): void {
    const safeMemberships = [...memberships];
    const currentSelection = this.selectedOperatorIdState();
    this.membershipsState.set(safeMemberships);
    this.loadedState.set(true);

    if (safeMemberships.length === 1) {
      this.selectedOperatorIdState.set(safeMemberships[0].operatorId);
      return;
    }
    if (
      !currentSelection ||
      !safeMemberships.some((membership) => membership.operatorId === currentSelection)
    ) {
      this.selectedOperatorIdState.set(null);
    }
  }
}
