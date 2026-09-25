import {
  autoNumber,
  placeTool,
  summaryOf,
  updateSeat,
  validateDraft
} from './seat-layout-draft';
import { SEAT_LAYOUT_TEMPLATES } from './seat-layout-templates';

describe('seat layout draft editing', () => {
  const base = () => ({
    ...SEAT_LAYOUT_TEMPLATES.find((template) => template.id === 'custom')!.build(),
    name: 'Custom coach'
  });

  it('places, edits, numbers, and deletes a seat', () => {
    let draft = placeTool(base(), 'SEATER', 1, 2, 1);
    expect(summaryOf(draft).total).toBe(1);
    expect(draft.seats[0].seatNumber).toBe('01A');
    draft = updateSeat(draft, '01A', { ...draft.seats[0], seatNumber: '12A', sellable: false });
    expect(draft.seats[0].seatNumber).toBe('12A');
    expect(summaryOf(draft).nonSellable).toBe(1);
    draft = placeTool(draft, 'ERASE', 1, 2, 1);
    expect(draft.seats).toEqual([]);
  });

  it('rejects a duplicate seat number', () => {
    let draft = placeTool(base(), 'SEATER', 1, 2, 1);
    draft = placeTool(draft, 'SEATER', 1, 2, 2);
    draft = updateSeat(draft, draft.seats[1].seatNumber, { ...draft.seats[1], seatNumber: draft.seats[0].seatNumber });
    expect(validateDraft(draft)).toContain('Seat number already exists.');
  });

  it('rejects overlapping spans and span outside the grid', () => {
    let draft = placeTool(base(), 'SLEEPER', 1, 2, 1);
    draft = placeTool(draft, 'SEATER', 1, 5, 1);
    const sleeper = draft.seats.find((seat) => seat.seatType === 'SLEEPER')!;
    draft = updateSeat(draft, sleeper.seatNumber, { ...sleeper, spanRows: 4 });
    expect(validateDraft(draft)).toContain('Seat positions overlap.');
    draft = {
      ...base(),
      seats: [
        {
          seatNumber: '01L',
          seatType: 'SLEEPER',
          deckNumber: 1,
          rowNumber: 8,
          columnNumber: 1,
          sellable: true,
          orientation: 'HORIZONTAL',
          spanRows: 2,
          spanColumns: 1
        }
      ]
    };
    expect(validateDraft(draft)).toContain('Seat span is outside the layout dimensions.');
  });

  it('places a horizontal sleeper berth that spans two rows', () => {
    const draft = placeTool(base(), 'SLEEPER', 1, 2, 1);
    expect(draft.seats[0].orientation).toBe('HORIZONTAL');
    expect(draft.seats[0].spanRows).toBe(2);
    expect(draft.seats[0].spanColumns).toBe(1);
  });

  it('places a marker and keeps the live summary current', () => {
    const draft = placeTool(base(), 'DOOR', 1, 4, 1);
    expect(draft.markers.some((marker) => marker.type === 'DOOR' && marker.rowNumber === 4)).toBeTrue();
    expect(summaryOf(draft).total).toBe(0);
  });

  it('numbers sleeper berths with L and U suffixes', () => {
    const draft = autoNumber({
      name: 'Berths',
      layoutType: 'SLEEPER',
      version: 1,
      deckCount: 2,
      rowCount: 2,
      columnCount: 1,
      markers: [],
      seats: [
        {
          seatNumber: 'x',
          seatType: 'SLEEPER_LOWER',
          deckNumber: 1,
          rowNumber: 1,
          columnNumber: 1,
          sellable: true,
          orientation: 'HORIZONTAL',
          spanRows: 1,
          spanColumns: 1
        },
        {
          seatNumber: 'y',
          seatType: 'SLEEPER_UPPER',
          deckNumber: 2,
          rowNumber: 1,
          columnNumber: 1,
          sellable: true,
          orientation: 'HORIZONTAL',
          spanRows: 1,
          spanColumns: 1
        }
      ]
    });
    expect(draft.seats.map((seat) => seat.seatNumber)).toEqual(['01L', '01U']);
  });
});
