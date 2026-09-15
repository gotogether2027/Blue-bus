package in.bluebustickets.bluebus.operator.api;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.operator.api.dto.CreateOperatorMemberRequest;
import in.bluebustickets.bluebus.operator.api.dto.OperatorMemberResponse;
import in.bluebustickets.bluebus.operator.api.dto.UpdateOperatorMemberRequest;
import in.bluebustickets.bluebus.operator.application.OperatorMembershipAdminService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/{operatorId}/members")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorMemberController {

    private final OperatorMembershipAdminService membershipAdminService;

    public OperatorMemberController(OperatorMembershipAdminService membershipAdminService) {
        this.membershipAdminService = membershipAdminService;
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<OperatorMemberResponse> list(@PathVariable UUID operatorId) {
        return membershipAdminService.list(operatorId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OperatorMemberResponse add(
            @PathVariable UUID operatorId,
            @Valid @RequestBody CreateOperatorMemberRequest request) {
        return membershipAdminService.add(operatorId, request.getUserId(), request.getRole());
    }

    @PatchMapping("/{userId}")
    @ResponseStatus(HttpStatus.OK)
    public OperatorMemberResponse update(
            @PathVariable UUID operatorId,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateOperatorMemberRequest request) {
        if (!request.hasSupportedField()) {
            throw new IllegalArgumentException("At least one of role or status is required.");
        }
        return membershipAdminService.update(
                operatorId,
                userId,
                request.getRole(),
                request.hasRole(),
                request.getStatus(),
                request.hasStatus());
    }

    @PostMapping("/{userId}/deactivate")
    @ResponseStatus(HttpStatus.OK)
    public OperatorMemberResponse deactivate(
            @PathVariable UUID operatorId,
            @PathVariable UUID userId) {
        return membershipAdminService.deactivate(operatorId, userId);
    }
}
