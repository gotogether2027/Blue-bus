export function newIdempotencyKey(): string {
  return `bb-${crypto.randomUUID()}`;
}
