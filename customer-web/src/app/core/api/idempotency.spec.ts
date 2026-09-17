import { newIdempotencyKey } from './idempotency';

describe('idempotency keys', () => {
  it('creates a stable-format client key under 100 characters', () => {
    const first = newIdempotencyKey();
    const second = newIdempotencyKey();
    expect(first.startsWith('bb-')).toBeTrue();
    expect(first.length).toBeLessThan(100);
    expect(first).not.toEqual(second);
  });
});
