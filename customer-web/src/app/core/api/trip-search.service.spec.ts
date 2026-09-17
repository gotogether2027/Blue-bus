import { toTripSearchParams } from './trip-search.service';

describe('toTripSearchParams', () => {
  it('maps origin, destination, and ISO service date only', () => {
    const params = toTripSearchParams({
      originLocationId: '11111111-1111-1111-1111-111111111111',
      destinationLocationId: '22222222-2222-2222-2222-222222222222',
      serviceDate: '2026-09-18'
    });

    expect(params.keys().sort()).toEqual(['destinationLocationId', 'originLocationId', 'serviceDate']);
    expect(params.get('originLocationId')).toBe('11111111-1111-1111-1111-111111111111');
    expect(params.get('destinationLocationId')).toBe('22222222-2222-2222-2222-222222222222');
    expect(params.get('serviceDate')).toBe('2026-09-18');
    expect(params.get('passengerCount')).toBeNull();
  });
});
