package in.bluebustickets.bluebus.operator.api;

import java.util.UUID;

import in.bluebustickets.bluebus.operator.api.dto.OperatorProfileResponse;
import in.bluebustickets.bluebus.operator.api.dto.UpdateOperatorSupportContactRequest;
import in.bluebustickets.bluebus.operator.application.OperatorPortalService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/{operatorId}")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorPortalController {

    private final OperatorPortalService operatorPortalService;

    public OperatorPortalController(OperatorPortalService operatorPortalService) {
        this.operatorPortalService = operatorPortalService;
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public OperatorProfileResponse get(@PathVariable UUID operatorId) {
        return operatorPortalService.get(operatorId);
    }

    @PatchMapping
    @ResponseStatus(HttpStatus.OK)
    public OperatorProfileResponse updateSupportContact(
            @PathVariable UUID operatorId,
            @Valid @RequestBody UpdateOperatorSupportContactRequest request) {
        return operatorPortalService.updateSupportContact(
                operatorId,
                request.getSupportEmail(),
                request.getSupportPhoneE164());
    }
}
