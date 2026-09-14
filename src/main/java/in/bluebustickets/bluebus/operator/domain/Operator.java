package in.bluebustickets.bluebus.operator.domain;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Entity
@Table(name = "operators")
public class Operator extends AuditableEntity {
    @NotBlank @Column(name = "legal_name", nullable = false, length = 255) private String legalName;
    @NotBlank @Column(name = "display_name", nullable = false, length = 255) private String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private OperatorStatus status = OperatorStatus.PENDING;
    @Email @Column(name = "support_email", length = 320) private String supportEmail;
    @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "must be an E.164 phone number")
    @Column(name = "support_phone_e164", length = 20) private String supportPhoneE164;
    protected Operator() { }
    public Operator(String legalName, String displayName) { this.legalName = legalName; this.displayName = displayName; }
    public String getLegalName() { return legalName; }
}
