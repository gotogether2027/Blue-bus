package in.bluebustickets.bluebus.identity.domain;

import java.time.Instant;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "user_roles")
public class UserRole {
    @EmbeddedId private UserRoleId id;
    @MapsId("user") @ManyToOne(optional = false) @JoinColumn(name = "user_id") private User user;
    @MapsId("role") @ManyToOne(optional = false) @JoinColumn(name = "role_id") private Role role;
    @NotNull private Instant grantedAt = Instant.now();
    protected UserRole() { }
    public UserRole(User user, Role role) {
        if (role.getScope() != RoleScope.PLATFORM) throw new IllegalArgumentException("User roles require PLATFORM scope");
        this.user = user; this.role = role; this.id = new UserRoleId(user.getId(), role.getId());
    }
}
