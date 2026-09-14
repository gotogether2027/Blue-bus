package in.bluebustickets.bluebus.identity.repository;

import java.util.Optional;
import java.util.UUID;
import in.bluebustickets.bluebus.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserRepository extends JpaRepository<User, UUID> { Optional<User> findByEmailIgnoreCase(String email); }
