package in.bluebustickets.bluebus.fleet.domain;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

@Entity
@Table(name = "bus_types")
public class BusType extends AuditableEntity {
    @NotBlank @Column(nullable = false, unique = true, length = 60) private String code;
    @NotBlank @Column(name = "display_name", nullable = false, length = 120) private String displayName;
    @Column(nullable = false) private boolean active = true;
    protected BusType() { }
    public BusType(String code, String displayName) { this.code = code; this.displayName = displayName; }
}
