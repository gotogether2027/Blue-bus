package in.bluebustickets.bluebus.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.bluebustickets.bluebus.booking.domain.BookingCancellation;
import in.bluebustickets.bluebus.booking.repository.BookingCancellationRepository;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.rabbit.ProcessedEventRepository;
import in.bluebustickets.bluebus.foundation.outbox.rabbit.RabbitMqProperties;
import in.bluebustickets.bluebus.notification.api.dto.NotificationPageResponse;
import in.bluebustickets.bluebus.notification.api.dto.NotificationPreferenceResponse;
import in.bluebustickets.bluebus.notification.api.dto.NotificationResponse;
import in.bluebustickets.bluebus.notification.domain.Notification;
import in.bluebustickets.bluebus.notification.domain.NotificationChannel;
import in.bluebustickets.bluebus.notification.domain.NotificationEventType;
import in.bluebustickets.bluebus.notification.domain.NotificationPreference;
import in.bluebustickets.bluebus.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates customer notifications from existing outbox events. Idempotent via
 * {@code processed_events} and unique (user, event type, channel, source/logical key).
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class NotificationApplicationService {

    public static final String CONSUMER_NAME = RabbitMqProperties.NOTIFICATION_CONSUMER;

    private static final Logger log = LoggerFactory.getLogger(NotificationApplicationService.class);

    private final ProcessedEventRepository processedEventRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceService preferenceService;
    private final NotificationRecipientResolver recipientResolver;
    private final NotificationTemplateRegistry templateRegistry;
    private final BookingCancellationRepository cancellationRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NotificationApplicationService(
            ProcessedEventRepository processedEventRepository,
            NotificationRepository notificationRepository,
            NotificationPreferenceService preferenceService,
            NotificationRecipientResolver recipientResolver,
            NotificationTemplateRegistry templateRegistry,
            BookingCancellationRepository cancellationRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.processedEventRepository = processedEventRepository;
        this.notificationRepository = notificationRepository;
        this.preferenceService = preferenceService;
        this.recipientResolver = recipientResolver;
        this.templateRegistry = templateRegistry;
        this.cancellationRepository = cancellationRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public enum Outcome {
        PROCESSED,
        DUPLICATE,
        IGNORED
    }

    @Transactional
    public Outcome processOutboxEvent(OutboxEvent event) {
        if (event == null || event.getId() == null || event.getEventType() == null || event.getEventType().isBlank()) {
            throw new IllegalArgumentException("outbox event id and type are required");
        }

        if (!NotificationEventType.isPublishableOutboxType(event.getEventType())) {
            log.info(
                    "Notification consumer ignored unknown event type eventId={} eventType={}",
                    event.getId(),
                    event.getEventType());
            return Outcome.IGNORED;
        }

        Instant now = clock.instant();
        int inserted = processedEventRepository.tryInsert(event.getId(), CONSUMER_NAME, now);
        if (inserted == 0) {
            log.info(
                    "Notification consumer duplicate eventId={} eventType={}",
                    event.getId(),
                    event.getEventType());
            return Outcome.DUPLICATE;
        }

        List<NotificationEventType> types = notificationTypesFor(event);
        int created = 0;
        for (NotificationEventType type : types) {
            created += createForType(event, type, now);
        }
        log.info(
                "Notification consumer success eventId={} eventType={} notificationsCreated={}",
                event.getId(),
                event.getEventType(),
                created);
        return Outcome.PROCESSED;
    }

    @Transactional(readOnly = true)
    public NotificationPageResponse listOwned(UUID userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        Page<Notification> result = notificationRepository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(safePage, safeSize));
        return new NotificationPageResponse(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public NotificationPreferenceResponse preferencesFor(UUID userId) {
        NotificationPreference preference = preferenceService.effectiveFor(userId);
        return new NotificationPreferenceResponse(
                preference.getUserId(),
                preference.isEmailEnabled(),
                preference.isSmsEnabled(),
                preference.isWhatsappEnabled());
    }

    private int createForType(OutboxEvent event, NotificationEventType type, Instant now) {
        var recipient = recipientResolver.resolve(event, type).orElse(null);
        if (recipient == null) {
            log.info(
                    "Notification skipped missing recipient eventId={} notificationType={}",
                    event.getId(),
                    type);
            return 0;
        }
        String logicalKey = logicalKey(type, event, recipient);
        Map<String, String> values = templateValues(recipient);
        String payload = payloadJson(event, recipient);
        int created = 0;
        for (NotificationChannel channel : NotificationChannel.values()) {
            if (!preferenceService.isChannelEnabled(recipient.userId(), channel)) {
                continue;
            }
            NotificationTemplate template = templateRegistry.require(type, channel);
            int insertedNotification = notificationRepository.insertIfAbsent(
                    UUID.randomUUID(),
                    recipient.userId(),
                    event.getId(),
                    type.name(),
                    channel.name(),
                    template.code(),
                    logicalKey,
                    template.renderSubject(values),
                    template.renderBody(values),
                    payload,
                    now);
            if (insertedNotification > 0) {
                created++;
            }
        }
        return created;
    }

    private List<NotificationEventType> notificationTypesFor(OutboxEvent event) {
        List<NotificationEventType> types = new ArrayList<>();
        NotificationEventType primary = NotificationEventType.valueOf(event.getEventType());
        types.add(primary);
        if (primary == NotificationEventType.BOOKING_CANCELLED && isTripCancellation(event)) {
            types.add(NotificationEventType.TRIP_CANCELLED);
        }
        return types;
    }

    private boolean isTripCancellation(OutboxEvent event) {
        return cancellationFor(event)
                .map(cancellation ->
                        BookingCancellation.TRIP_CANCELLED_FULL_REFUND_POLICY_CODE.equals(cancellation.getPolicyCode())
                                || BookingCancellation.TRIP_CANCELLED_UNPAID_POLICY_CODE.equals(cancellation.getPolicyCode()))
                .orElse(false);
    }

    private java.util.Optional<BookingCancellation> cancellationFor(OutboxEvent event) {
        JsonNode payload = readPayload(event);
        if (payload != null && payload.hasNonNull("cancellationId")) {
            try {
                return cancellationRepository.findById(UUID.fromString(payload.get("cancellationId").asText()));
            } catch (IllegalArgumentException ignored) {
                return cancellationRepository.findByBookingId(event.getAggregateId());
            }
        }
        return cancellationRepository.findByBookingId(event.getAggregateId());
    }

    private String logicalKey(
            NotificationEventType type,
            OutboxEvent event,
            NotificationRecipientResolver.ResolvedRecipient recipient) {
        return switch (type) {
            case PAYMENT_FAILED -> type.name() + ":" + event.getAggregateId();
            case BOOKING_CONFIRMED, BOOKING_CANCELLED, TRIP_CANCELLED ->
                    type.name() + ":" + (recipient.bookingId() == null ? event.getAggregateId() : recipient.bookingId());
            case TICKET_ISSUED ->
                    type.name() + ":" + (recipient.ticketId() == null ? event.getAggregateId() : recipient.ticketId());
            case REFUND_REQUESTED, REFUND_SUCCEEDED, REFUND_FAILED ->
                    type.name() + ":" + (recipient.refundId() == null ? event.getAggregateId() : recipient.refundId());
        };
    }

    private Map<String, String> templateValues(NotificationRecipientResolver.ResolvedRecipient recipient) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("bookingId", recipient.bookingId() == null ? "" : recipient.bookingId().toString());
        values.put("bookingReference", recipient.bookingReference() == null ? "" : recipient.bookingReference());
        values.put("ticketNumber", recipient.ticketNumber() == null ? "" : recipient.ticketNumber());
        return values;
    }

    private String payloadJson(OutboxEvent event, NotificationRecipientResolver.ResolvedRecipient recipient) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sourceEventId", event.getId().toString());
            payload.put("sourceEventType", event.getEventType());
            payload.put("bookingId", recipient.bookingId());
            payload.put("bookingReference", recipient.bookingReference());
            payload.put("ticketId", recipient.ticketId());
            payload.put("ticketNumber", recipient.ticketNumber());
            payload.put("refundId", recipient.refundId());
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            return event.getPayloadJson();
        }
    }

    private JsonNode readPayload(OutboxEvent event) {
        try {
            return objectMapper.readTree(event.getPayloadJson());
        } catch (Exception exception) {
            return null;
        }
    }

    private NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getEventType().name(),
                notification.getChannel().name(),
                notification.getTemplateCode(),
                notification.getSubject(),
                notification.getBody(),
                notification.getStatus().name(),
                notification.getCreatedAt(),
                notification.getSentAt());
    }
}
