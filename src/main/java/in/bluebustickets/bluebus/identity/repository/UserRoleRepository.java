package in.bluebustickets.bluebus.identity.repository;

import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.domain.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> { }
