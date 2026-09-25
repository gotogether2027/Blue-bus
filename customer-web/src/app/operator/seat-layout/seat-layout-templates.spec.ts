import { SEAT_LAYOUT_TEMPLATES } from './seat-layout-templates';
import { summaryOf, validateDraft } from './seat-layout-draft';

describe('seat layout templates', () => {
  it('builds the required template catalogue', () => {
    expect(SEAT_LAYOUT_TEMPLATES.map((template) => template.id)).toEqual([
      'seater-2-2-32',
      'seater-2-2-40',
      'seater-2-1-30',
      'seater-2-1-36',
      'sleeper-2-2',
      'sleeper-2-1',
      'sleeper-1-1',
      'mixed-2-1',
      'multi-sleeper',
      'multi-mixed',
      'custom'
    ]);
  });

  it('creates a 32-seat 2+2 seater with an aisle and numbered seats', () => {
    const draft = SEAT_LAYOUT_TEMPLATES[0].build();
    expect(summaryOf(draft).total).toBe(32);
    expect(summaryOf(draft).seaters).toBe(32);
    expect(draft.seats.map((seat) => seat.seatNumber)).toContain('01A');
    expect(draft.markers.some((marker) => marker.type === 'AISLE')).toBeTrue();
    expect(draft.markers.some((marker) => marker.type === 'DRIVER')).toBeTrue();
    expect(validateDraft(draft)).toEqual([]);
  });

  it('creates a 30-seat 2+1 seater', () => {
    const draft = SEAT_LAYOUT_TEMPLATES[2].build();
    expect(summaryOf(draft).total).toBe(30);
    expect(validateDraft(draft)).toEqual([]);
  });

  it('renders sleeper berths as spans rather than square seats', () => {
    const draft = SEAT_LAYOUT_TEMPLATES[4].build();
    expect(summaryOf(draft).sleepers).toBe(draft.seats.length);
    expect(draft.seats.every((seat) => seat.spanRows === 2)).toBeTrue();
    expect(draft.seats.some((seat) => seat.orientation === 'VERTICAL')).toBeTrue();
    expect(validateDraft(draft)).toEqual([]);
  });

  it('builds a mixed seater and sleeper layout', () => {
    const draft = SEAT_LAYOUT_TEMPLATES[7].build();
    expect(summaryOf(draft).seaters).toBeGreaterThan(0);
    expect(summaryOf(draft).sleepers).toBeGreaterThan(0);
    expect(validateDraft(draft)).toEqual([]);
  });

  it('builds a multi-deck sleeper', () => {
    const draft = SEAT_LAYOUT_TEMPLATES[8].build();
    expect(draft.deckCount).toBe(2);
    expect(summaryOf(draft).lowerDeck).toBeGreaterThan(0);
    expect(summaryOf(draft).upperDeck).toBeGreaterThan(0);
    expect(validateDraft(draft)).toEqual([]);
  });

  it('starts custom with no seats', () => {
    const draft = SEAT_LAYOUT_TEMPLATES[10].build();
    expect(draft.layoutType).toBe('CUSTOM');
    expect(draft.seats).toEqual([]);
    expect(validateDraft({ ...draft, name: 'Custom' })).toContain('Layout must contain at least one seat.');
  });
});
