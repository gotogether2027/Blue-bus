package in.bluebustickets.bluebus.operator.domain;

import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleScope;
import in.bluebustickets.bluebus.identity.domain.User;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "operator_users")
public class OperatorUser {

    @EmbeddedId
    private OperatorUserId id;

    @MapsId("operator")
    @ManyToOne(optional = false)
    @JoinColumn(name = "operator_id")
    private Operator operator;

    @MapsId("user")
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @NotNull
    @ManyToOne(optional = false)
    @JoinColumn(name = "role_id")
    private Role role;

    @NotNull
    @Enumerated(EnumType.STRING)
    private OperatorUserStatus status = OperatorUserStatus.ACTIVE;

    protected OperatorUser() {
    }

    public OperatorUser(Operator operator, User user, Role role) {
        if (role.getScope() != RoleScope.OPERATOR) {
            throw new IllegalArgumentException("Operator users require OPERATOR scope");
        }
        this.operator = operator;
        this.user = user;
        this.role = role;
        this.id = new OperatorUserId(operator.getId(), user.getId());
    }

    public void deactivate() {
        this.status = OperatorUserStatus.INACTIVE;
    }

    public void activate() {
        this.status = OperatorUserStatus.ACTIVE;
    }

    public void assignRole(Role role) {
        if (role == null || role.getScope() != RoleScope.OPERATOR) {
            throw new IllegalArgumentException("Operator users require OPERATOR scope");
        }
        this.role = role;
    }

    public OperatorUserId getId() {
        return id;
    }

    public Operator getOperator() {
        return operator;
    }

    public User getUser() {
        return user;
    }

    public Role getRole() {
        return role;
    }

    public OperatorUserStatus getStatus() {
        return status;
    }
}
