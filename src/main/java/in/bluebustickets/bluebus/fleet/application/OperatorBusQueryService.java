package in.bluebustickets.bluebus.fleet.application;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.api.admin.dto.BusResponse;
import in.bluebustickets.bluebus.fleet.repository.BusRepository;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.operator.application.OperatorAuthorizationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorBusQueryService {

    private final OperatorAuthorizationService operatorAuthorizationService;
    private final BusRepository busRepository;

    public OperatorBusQueryService(
            OperatorAuthorizationService operatorAuthorizationService,
            BusRepository busRepository) {
        this.operatorAuthorizationService = operatorAuthorizationService;
        this.busRepository = busRepository;
    }

    @Transactional(readOnly = true)
    public List<BusResponse> list(UUID operatorId) {
        operatorAuthorizationService.requireMember(operatorId, RoleCode.OPERATOR_ADMIN, RoleCode.OPERATOR_STAFF);
        return busRepository.findByOperator_IdOrderByRegistrationNumberAsc(operatorId).stream()
                .map(BusResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public BusResponse get(UUID operatorId, UUID busId) {
        operatorAuthorizationService.requireMember(operatorId, RoleCode.OPERATOR_ADMIN, RoleCode.OPERATOR_STAFF);
        return BusResponse.from(busRepository.findByIdAndOperator_Id(busId, operatorId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound));
    }
}
