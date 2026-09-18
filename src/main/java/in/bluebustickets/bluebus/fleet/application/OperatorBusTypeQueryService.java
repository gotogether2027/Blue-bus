package in.bluebustickets.bluebus.fleet.application;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.api.admin.dto.BusTypeResponse;
import in.bluebustickets.bluebus.fleet.repository.BusTypeRepository;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.operator.application.OperatorAuthorizationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-safe reference data required to assign an eligible type to a bus.
 * Bus types remain platform-managed; operators can only read active choices.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorBusTypeQueryService {

    private final OperatorAuthorizationService operatorAuthorizationService;
    private final BusTypeRepository busTypeRepository;

    public OperatorBusTypeQueryService(
            OperatorAuthorizationService operatorAuthorizationService,
            BusTypeRepository busTypeRepository) {
        this.operatorAuthorizationService = operatorAuthorizationService;
        this.busTypeRepository = busTypeRepository;
    }

    @Transactional(readOnly = true)
    public List<BusTypeResponse> listActive(UUID operatorId) {
        operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN, RoleCode.OPERATOR_STAFF);
        return busTypeRepository.findByActiveOrderByCodeAsc(true).stream()
                .map(BusTypeResponse::from)
                .toList();
    }
}
