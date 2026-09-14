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

    @NotBlank
    @Column(name = "legal_name", nullable = false, length = 255)
    private String legalName;

    @NotBlank
    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OperatorStatus status = OperatorStatus.PENDING;

    @Email
    @Column(name = "support_email", length = 320)
    private String supportEmail;

    @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "must be an E.164 phone number")
    @Column(name = "support_phone_e164", length = 20)
    private String supportPhoneE164;

    protected Operator() { }

    public Operator(String legalName, String displayName) {
        this.legalName = legalName;
        this.displayName = displayName;
    }

    public void updateProfile(String legalName, String displayName, String supportEmail, String supportPhoneE164) {
        if (legalName == null || legalName.isBlank()) {
            throw new IllegalArgumentException("Operator legal name is required");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Operator display name is required");
        }
        this.legalName = legalName;
        this.displayName = displayName;
        this.supportEmail = supportEmail;
        this.supportPhoneE164 = supportPhoneE164;
    }

    /**
     * Moves an operator into ACTIVE service. Allowed from PENDING, SUSPENDED, or INACTIVE.
     */
    public void activate() {
        if (status == OperatorStatus.ACTIVE) {
            return;
        }
        if (status != OperatorStatus.PENDING
                && status != OperatorStatus.SUSPENDED
                && status != OperatorStatus.INACTIVE) {
            throw new IllegalArgumentException("Operator cannot be activated from status " + status);
        }
        this.status = OperatorStatus.ACTIVE;
    }

    /**
     * Deactivates an operator. Allowed from PENDING, ACTIVE, or SUSPENDED.
     */
    public void deactivate() {
        if (status == OperatorStatus.INACTIVE) {
            return;
        }
        if (status != OperatorStatus.PENDING
                && status != OperatorStatus.ACTIVE
                && status != OperatorStatus.SUSPENDED) {
            throw new IllegalArgumentException("Operator cannot be deactivated from status " + status);
        }
        this.status = OperatorStatus.INACTIVE;
    }

    public boolean isEligibleForMasterData() {
        return status == OperatorStatus.PENDING || status == OperatorStatus.ACTIVE;
    }

    public String getLegalName() { return legalName; }
    public String getDisplayName() { return displayName; }
    public OperatorStatus getStatus() { return status; }
    public String getSupportEmail() { return supportEmail; }
    public String getSupportPhoneE164() { return supportPhoneE164; }
}
