package in.bluebustickets.bluebus.foundation.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Client identity for anonymous abuse buckets. Uses the servlet remote address only.
 * There is no trusted-proxy configuration in this application, so {@code X-Forwarded-For}
 * is ignored.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return "unknown";
        }
        return remote.trim();
    }
}
