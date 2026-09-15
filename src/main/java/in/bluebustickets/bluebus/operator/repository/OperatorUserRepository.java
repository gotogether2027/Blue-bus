package in.bluebustickets.bluebus.operator.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.operator.domain.OperatorStatus;
import in.bluebustickets.bluebus.operator.domain.OperatorUser;
import in.bluebustickets.bluebus.operator.domain.OperatorUserId;
import in.bluebustickets.bluebus.operator.domain.OperatorUserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperatorUserRepository extends JpaRepository<OperatorUser, OperatorUserId> {

    @Query("""
            select ou from OperatorUser ou
            join fetch ou.operator
            join fetch ou.role
            join fetch ou.user
            where ou.operator.id = :operatorId and ou.user.id = :userId
            """)
    Optional<OperatorUser> findByOperatorIdAndUserId(
            @Param("operatorId") UUID operatorId,
            @Param("userId") UUID userId);

    @Query("""
            select ou from OperatorUser ou
            join fetch ou.operator o
            join fetch ou.role
            where ou.user.id = :userId
              and ou.status = :membershipStatus
              and o.status = :operatorStatus
            order by o.displayName asc
            """)
    List<OperatorUser> findByUserIdAndStatusAndOperatorStatus(
            @Param("userId") UUID userId,
            @Param("membershipStatus") OperatorUserStatus membershipStatus,
            @Param("operatorStatus") OperatorStatus operatorStatus);

    @Query("""
            select ou from OperatorUser ou
            join fetch ou.user
            join fetch ou.role
            where ou.operator.id = :operatorId
            order by ou.user.firstName asc, ou.user.lastName asc nulls last, ou.user.id asc
            """)
    List<OperatorUser> findDetailedByOperatorIdOrderByUserName(@Param("operatorId") UUID operatorId);

    @Query("""
            select count(ou) from OperatorUser ou
            join ou.role r
            where ou.operator.id = :operatorId
              and ou.status = :status
              and r.code = :roleCode
            """)
    long countActiveOperatorAdmins(
            @Param("operatorId") UUID operatorId,
            @Param("status") OperatorUserStatus status,
            @Param("roleCode") RoleCode roleCode);
}
