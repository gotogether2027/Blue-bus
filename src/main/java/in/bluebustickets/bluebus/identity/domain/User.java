package in.bluebustickets.bluebus.identity.domain;

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
@Table(name = "users")
public class User extends AuditableEntity {

    @Email @Column(length = 320) private String email;
    @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "must be an E.164 phone number")
    @Column(name = "phone_e164", length = 20) private String phoneE164;
    @Column(name = "password_hash", length = 255) private String passwordHash;
    @NotBlank @Column(name = "first_name", nullable = false, length = 100) private String firstName;
    @Column(name = "last_name", length = 100) private String lastName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private UserStatus status = UserStatus.ACTIVE;

    protected User() { }
    public User(String email, String phoneE164, String firstName, String lastName) {
        this.email = email; this.phoneE164 = phoneE164; this.firstName = firstName; this.lastName = lastName;
    }
    public String getEmail() { return email; }
    public String getPhoneE164() { return phoneE164; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) {
        this.status = status == null ? UserStatus.ACTIVE : status;
    }
}
