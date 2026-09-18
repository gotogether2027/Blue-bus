package in.bluebustickets.bluebus.foundation.ratelimit;

/**
 * In-process rate-limit buckets. Limits are per application instance, not cluster-wide.
 */
public enum RateLimitCategory {
    AUTH,
    PUBLIC_API,
    HOLDS,
    WEBHOOK
}
