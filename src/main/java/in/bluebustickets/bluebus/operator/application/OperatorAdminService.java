package in.bluebustickets.bluebus.operator.application;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.domain.OperatorStatus;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorAdminService {

    private final OperatorRepository operatorRepository;

    public OperatorAdminService(OperatorRepository operatorRepository) {
        this.operatorRepository = operatorRepository;
    }

    @Transactional
    public Operator create(
            String legalName,
            String displayName,
            String supportEmail,
            String supportPhoneE164) {
        String normalizedLegalName = requireText(legalName, "Operator legal name is required");
        String normalizedDisplayName = requireText(displayName, "Operator display name is required");
        Operator operator = new Operator(normalizedLegalName, normalizedDisplayName);
        operator.updateProfile(
                normalizedLegalName,
                normalizedDisplayName,
                blankToNull(supportEmail),
                blankToNull(supportPhoneE164));
        return operatorRepository.save(operator);
    }

    @Transactional(readOnly = true)
    public Operator get(UUID id) {
        return operatorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Operator was not found."));
    }

    @Transactional(readOnly = true)
    public List<Operator> list(OperatorStatus status) {
        if (status == null) {
            return operatorRepository.findAllByOrderByDisplayNameAsc();
        }
        return operatorRepository.findByStatusOrderByDisplayNameAsc(status);
    }

    @Transactional
    public Operator update(
            UUID id,
            String legalName,
            String displayName,
            String supportEmail,
            String supportPhoneE164) {
        Operator operator = get(id);
        operator.updateProfile(
                requireText(legalName, "Operator legal name is required"),
                requireText(displayName, "Operator display name is required"),
                blankToNull(supportEmail),
                blankToNull(supportPhoneE164));
        return operator;
    }

    @Transactional
    public Operator activate(UUID id) {
        Operator operator = get(id);
        operator.activate();
        return operator;
    }

    @Transactional
    public Operator deactivate(UUID id) {
        Operator operator = get(id);
        operator.deactivate();
        return operator;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
