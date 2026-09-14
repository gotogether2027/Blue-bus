package in.bluebustickets.bluebus.identity.repository;

import java.util.Optional;
import java.util.UUID;
import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RoleRepository extends JpaRepository<Role, UUID> { Optional<Role> findByCode(RoleCode code); }
