package in.bluebustickets.bluebus.ticket.api;

import java.util.UUID;

import in.bluebustickets.bluebus.identity.application.CurrentUserService;
import in.bluebustickets.bluebus.ticket.api.dto.TicketResponse;
import in.bluebustickets.bluebus.ticket.application.TicketApplicationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TicketController {

    private final TicketApplicationService ticketApplicationService;
    private final CurrentUserService currentUserService;

    public TicketController(
            TicketApplicationService ticketApplicationService,
            CurrentUserService currentUserService) {
        this.ticketApplicationService = ticketApplicationService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/bookings/{bookingId}/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse issue(
            Authentication authentication,
            @PathVariable UUID bookingId) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        return ticketApplicationService.issueForBooking(userId, bookingId);
    }

    @GetMapping("/bookings/{bookingId}/ticket")
    public TicketResponse getByBooking(
            Authentication authentication,
            @PathVariable UUID bookingId) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        return ticketApplicationService.getOwnedByBooking(userId, bookingId);
    }

    @GetMapping("/tickets/{ticketId}")
    public TicketResponse get(
            Authentication authentication,
            @PathVariable UUID ticketId) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        return ticketApplicationService.getOwned(userId, ticketId);
    }
}
