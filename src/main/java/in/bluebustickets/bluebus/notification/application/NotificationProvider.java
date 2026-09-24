package in.bluebustickets.bluebus.notification.application;

import in.bluebustickets.bluebus.notification.domain.Notification;
import in.bluebustickets.bluebus.notification.domain.NotificationChannel;

/**
 * Channel adapter. Phase 1 has no real email/SMS/WhatsApp HTTP.
 */
public interface NotificationProvider {

    NotificationChannel channel();

    ProviderResult send(Notification notification);

    record ProviderResult(
            Outcome outcome,
            String providerMessageId,
            String failureCode,
            String failureMessage) {

        public enum Outcome {
            SUCCESS,
            RETRYABLE_FAILURE,
            NON_RETRYABLE_FAILURE
        }

        public static ProviderResult success(String providerMessageId) {
            return new ProviderResult(Outcome.SUCCESS, providerMessageId, null, null);
        }

        public static ProviderResult retryable(String failureCode, String failureMessage) {
            return new ProviderResult(Outcome.RETRYABLE_FAILURE, null, failureCode, failureMessage);
        }

        public static ProviderResult nonRetryable(String failureCode, String failureMessage) {
            return new ProviderResult(Outcome.NON_RETRYABLE_FAILURE, null, failureCode, failureMessage);
        }
    }
}
