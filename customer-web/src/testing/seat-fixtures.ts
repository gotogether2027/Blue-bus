import { TripSeatAvailabilitySeat } from '../app/core/api/models';

export const seatFixture = (
  overrides: Partial<TripSeatAvailabilitySeat> = {}
): TripSeatAvailabilitySeat => ({
  inventoryId: 'inv-1',
  seatNumber: 'L1',
  seatType: 'SEATER',
  deck: 1,
  row: 1,
  column: 1,
  physicalStatus: 'AVAILABLE',
  availability: 'AVAILABLE',
  ...overrides
});
