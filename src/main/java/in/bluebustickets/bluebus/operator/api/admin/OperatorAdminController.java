package in.bluebustickets.bluebus.operator.api.admin;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.operator.api.admin.dto.CreateOperatorRequest;
import in.bluebustickets.bluebus.operator.api.admin.dto.OperatorResponse;
import in.bluebustickets.bluebus.operator.api.admin.dto.UpdateOperatorRequest;
import in.bluebustickets.bluebus.operator.application.OperatorAdminService;
import in.bluebustickets.bluebus.operator.domain.OperatorStatus;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/operators")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorAdminController {

    private final OperatorAdminService operatorAdminService;

    public OperatorAdminController(OperatorAdminService operatorAdminService) {
        this.operatorAdminService = operatorAdminService;
    }

    @PostMapping
    public ResponseEntity<OperatorResponse> create(@Valid @RequestBody CreateOperatorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(OperatorResponse.from(operatorAdminService.create(
                request.legalName(),
                request.displayName(),
                request.supportEmail(),
                request.supportPhoneE164())));
    }

    @GetMapping("/{id}")
    public OperatorResponse get(@PathVariable UUID id) {
        return OperatorResponse.from(operatorAdminService.get(id));
    }

    @GetMapping
    public List<OperatorResponse> list(@RequestParam(required = false) OperatorStatus status) {
        return operatorAdminService.list(status).stream().map(OperatorResponse::from).toList();
    }

    @PutMapping("/{id}")
    public OperatorResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateOperatorRequest request) {
        return OperatorResponse.from(operatorAdminService.update(
                id,
                request.legalName(),
                request.displayName(),
                request.supportEmail(),
                request.supportPhoneE164()));
    }

    @PostMapping("/{id}/activate")
    public OperatorResponse activate(@PathVariable UUID id) {
        return OperatorResponse.from(operatorAdminService.activate(id));
    }

    @PostMapping("/{id}/deactivate")
    public OperatorResponse deactivate(@PathVariable UUID id) {
        return OperatorResponse.from(operatorAdminService.deactivate(id));
    }
}
