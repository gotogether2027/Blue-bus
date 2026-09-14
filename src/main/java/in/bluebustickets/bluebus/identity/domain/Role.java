package in.bluebustickets.bluebus.identity.domain;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "roles")
public class Role extends AuditableEntity {
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, unique = true, length = 60) private RoleCode code;
    @NotBlank @Column(nullable = false, length = 120) private String name;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private RoleScope scope;
    @Column(length = 500) private String description;
    protected Role() { }
    public Role(RoleCode code, String name, RoleScope scope) {
        if (code.requiredScope() != scope) {
            throw new IllegalArgumentException("Role code must use its approved scope");
        }
        this.code = code;
        this.name = name;
        this.scope = scope;
    }
    public RoleCode getCode() { return code; }
    public RoleScope getScope() { return scope; }
}
