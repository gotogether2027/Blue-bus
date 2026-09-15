package in.bluebustickets.bluebus.operator.application;

import java.util.UUID;

import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.operator.domain.OperatorStatus;

/**
 * Authoritative operator-membership snapshot for a single request. Built from the database,
 * never from JWT role or operator claims.
 */
public record OperatorAccess(
        UUID userId,
        UUID operatorId,
        RoleCode membershipRole,
        OperatorStatus operatorStatus) {
}
