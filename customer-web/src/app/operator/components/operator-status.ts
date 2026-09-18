export type StatusTone = 'neutral' | 'success' | 'warn' | 'danger' | 'info';

export function operatorStatusTone(status: string | null | undefined): StatusTone {
  switch (status) {
    case 'ACTIVE':
    case 'AVAILABLE':
    case 'CONFIRMED':
    case 'COMPLETED':
      return 'success';
    case 'PENDING':
    case 'PENDING_PAYMENT':
    case 'INITIATED':
    case 'MAINTENANCE':
    case 'REFUND_PENDING':
    case 'DRAFT':
    case 'CLOSED':
      return 'warn';
    case 'SCHEDULED':
    case 'ON_SALE':
    case 'DEPARTED':
    case 'OPERATOR_ADMIN':
    case 'OPERATOR_STAFF':
      return 'info';
    case 'INACTIVE':
    case 'SUSPENDED':
    case 'CANCELLED':
    case 'EXPIRED':
    case 'REFUNDED':
    case 'BLOCKED':
      return 'danger';
    default:
      return 'neutral';
  }
}

export function operatorRoleLabel(role: string): string {
  switch (role) {
    case 'OPERATOR_ADMIN':
      return 'Operator admin';
    case 'OPERATOR_STAFF':
      return 'Operator staff';
    default:
      return role;
  }
}
