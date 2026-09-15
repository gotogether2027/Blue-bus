package in.bluebustickets.bluebus.operator.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Allow-list PATCH body for operator support contacts. Unknown fields, including identity and
 * tenancy identifiers, are rejected rather than ignored.
 */
public class UpdateOperatorSupportContactRequest {

    @Email
    @Size(max = 320)
    private String supportEmail;

    @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "must be an E.164 phone number")
    @Size(max = 20)
    private String supportPhoneE164;

    public String getSupportEmail() {
        return supportEmail;
    }

    public void setSupportEmail(String supportEmail) {
        this.supportEmail = supportEmail;
    }

    public String getSupportPhoneE164() {
        return supportPhoneE164;
    }

    public void setSupportPhoneE164(String supportPhoneE164) {
        this.supportPhoneE164 = supportPhoneE164;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
