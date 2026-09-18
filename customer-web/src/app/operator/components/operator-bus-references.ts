import { OperatorBusType, OperatorSeatLayout } from '../models/operator.models';

export function eligibleActiveBusTypes(busTypes: OperatorBusType[]): OperatorBusType[] {
  return busTypes.filter((busType) => busType.active);
}

export function eligiblePublishedSeatLayouts(
  layouts: OperatorSeatLayout[],
  operatorId: string
): OperatorSeatLayout[] {
  return layouts.filter(
    (layout) => layout.status === 'PUBLISHED' && layout.operatorId === operatorId
  );
}
