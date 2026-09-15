package in.bluebustickets.bluebus.scheduling.api.operator.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Size;

/**
 * Allow-list PATCH for operator route name and endpoints.
 * Unknown fields (including code, operatorId, status, routeId) are rejected.
 */
public class UpdateOperatorRouteRequest {

    @Size(max = 160)
    private String name;
    private UUID sourceLocationId;
    private UUID destinationLocationId;

    private boolean namePresent;
    private boolean sourceLocationIdPresent;
    private boolean destinationLocationIdPresent;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    public UUID getSourceLocationId() {
        return sourceLocationId;
    }

    public void setSourceLocationId(UUID sourceLocationId) {
        this.sourceLocationId = sourceLocationId;
        this.sourceLocationIdPresent = true;
    }

    public UUID getDestinationLocationId() {
        return destinationLocationId;
    }

    public void setDestinationLocationId(UUID destinationLocationId) {
        this.destinationLocationId = destinationLocationId;
        this.destinationLocationIdPresent = true;
    }

    public boolean hasName() {
        return namePresent;
    }

    public boolean hasSourceLocationId() {
        return sourceLocationIdPresent;
    }

    public boolean hasDestinationLocationId() {
        return destinationLocationIdPresent;
    }

    public boolean hasSupportedField() {
        return namePresent || sourceLocationIdPresent || destinationLocationIdPresent;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
