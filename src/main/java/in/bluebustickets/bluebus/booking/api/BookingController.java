package in.bluebustickets.bluebus.booking.api;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.api.dto.BookingResponse;
import in.bluebustickets.bluebus.booking.api.dto.CreateBookingRequest;
import in.bluebustickets.bluebus.booking.application.BookingApplicationService;
import in.bluebustickets.bluebus.identity.application.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated customer booking APIs. Ownership is always derived from the JWT subject.
 */
@RestController
@RequestMapping("/api/v1/bookings")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class BookingController {

    private final BookingApplicationService bookingApplicationService;
    private final CurrentUserService currentUserService;

    public BookingController(
            BookingApplicationService bookingApplicationService,
            CurrentUserService currentUserService) {
        this.bookingApplicationService = bookingApplicationService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(
            Authentication authentication, @Valid @RequestBody CreateBookingRequest request) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        return bookingApplicationService.createFromHold(userId, request);
    }

    @GetMapping("/{bookingId}")
    @ResponseStatus(HttpStatus.OK)
    public BookingResponse get(Authentication authentication, @PathVariable UUID bookingId) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        return bookingApplicationService.getOwnedBooking(userId, bookingId);
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<BookingResponse> list(Authentication authentication) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        return bookingApplicationService.listOwnedBookings(userId);
    }
}
