package in.bluebustickets.bluebus.notification.application;

import java.util.Map;

import in.bluebustickets.bluebus.notification.domain.NotificationChannel;
import in.bluebustickets.bluebus.notification.domain.NotificationEventType;

public record NotificationTemplate(
        NotificationEventType eventType,
        NotificationChannel channel,
        String code,
        String subject,
        String body) {

    public String renderSubject(Map<String, String> values) {
        return apply(subject, values);
    }

    public String renderBody(Map<String, String> values) {
        return apply(body, values);
    }

    private static String apply(String template, Map<String, String> values) {
        if (template == null) {
            return null;
        }
        String rendered = template;
        for (var entry : values.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return rendered;
    }
}
