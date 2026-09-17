package in.bluebustickets.bluebus.identity.repository;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.domain.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    @Query("""
            select ur from UserRole ur
            join fetch ur.role
            where ur.user.id = :userId
            """)
    List<UserRole> findByUserIdWithRole(@Param("userId") UUID userId);

    boolean existsByUser_IdAndRole_Id(UUID userId, UUID roleId);
}
