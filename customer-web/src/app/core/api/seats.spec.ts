import {
  groupSeatsByLayout,
  isSeatSelectable,
  toCreateBookingRequest,
  toCreateHoldRequest,
  toggleSeatSelection,
  validatePassengers
} from './seats';
import { seatFixture } from '../../../testing/seat-fixtures';

describe('seat selection rules', () => {
  it('allows only physically available seats that are journey-available', () => {
    expect(isSeatSelectable(seatFixture())).toBeTrue();
    expect(isSeatSelectable(seatFixture({ physicalStatus: 'BLOCKED' }))).toBeFalse();
    expect(isSeatSelectable(seatFixture({ availability: 'UNAVAILABLE' }))).toBeFalse();
  });

  it('toggles selectable seats and ignores blocked or taken seats', () => {
    const available = seatFixture({ inventoryId: 'a' });
    const blocked = seatFixture({ inventoryId: 'b', physicalStatus: 'BLOCKED' });
    const taken = seatFixture({ inventoryId: 'c', availability: 'UNAVAILABLE' });
    let selected: string[] = [];
    selected = toggleSeatSelection(selected, available);
    selected = toggleSeatSelection(selected, blocked);
    selected = toggleSeatSelection(selected, taken);
    expect(selected).toEqual(['a']);
    selected = toggleSeatSelection(selected, available);
    expect(selected).toEqual([]);
  });

  it('groups seats by backend deck, row, and column fields', () => {
    const decks = groupSeatsByLayout([
      seatFixture({ inventoryId: '2', deck: 1, row: 2, column: 1, seatNumber: 'L2' }),
      seatFixture({ inventoryId: '1', deck: 1, row: 1, column: 2, seatNumber: 'L1B' }),
      seatFixture({ inventoryId: '0', deck: 1, row: 1, column: 1, seatNumber: 'L1A' })
    ]);
    expect(decks[0].deck).toBe(1);
    expect(decks[0].rows[0].seats.map((seat) => seat.seatNumber)).toEqual(['L1A', 'L1B']);
  });
});

describe('hold request mapping', () => {
  it('maps origin, destination, seat IDs, and idempotency key from the hold contract', () => {
    expect(
      toCreateHoldRequest({
        originStopId: 'o',
        destinationStopId: 'd',
        seatInventoryIds: ['s1', 's2'],
        idempotencyKey: 'bb-key'
      })
    ).toEqual({
      originStopId: 'o',
      destinationStopId: 'd',
      seatInventoryIds: ['s1', 's2'],
      idempotencyKey: 'bb-key'
    });
  });
});

describe('passenger validation and booking request mapping', () => {
  it('requires one named passenger per held seat', () => {
    expect(
      validatePassengers(['s1'], [{ seatInventoryId: 's1', fullName: '', age: null, gender: null }])
    ).toContain('full name');
    expect(
      validatePassengers(
        ['s1', 's2'],
        [{ seatInventoryId: 's1', fullName: 'Asha Rao', age: 32, gender: 'FEMALE' }]
      )
    ).toContain('one passenger');
  });

  it('maps the booking-create body including a stable idempotency key', () => {
    const request = toCreateBookingRequest({
      holdId: 'hold-1',
      originStopId: 'o',
      destinationStopId: 'd',
      idempotencyKey: 'bb-book-1',
      passengers: [{ seatInventoryId: 's1', fullName: ' Asha Rao ', age: 32, gender: 'FEMALE' }]
    });
    expect(request).toEqual({
      holdId: 'hold-1',
      originStopId: 'o',
      destinationStopId: 'd',
      idempotencyKey: 'bb-book-1',
      passengers: [{ seatInventoryId: 's1', fullName: 'Asha Rao', age: 32, gender: 'FEMALE' }]
    });
  });
});
