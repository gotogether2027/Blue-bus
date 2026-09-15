package in.bluebustickets.bluebus.scheduling.api.operator.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.RouteStopDefinitionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create an operator-owned route. Path {@code operatorId} is authoritative; body operatorId is rejected.
 */
public class CreateOperatorRouteRequest {

    @NotBlank
    @Size(max = 60)
    private String code;

    @NotBlank
    @Size(max = 160)
    private String name;

    @NotNull
    private UUID sourceLocationId;

    @NotNull
    private UUID destinationLocationId;

    @Valid
    private List<CreateOperatorRouteStopRequest> stops;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getSourceLocationId() {
        return sourceLocationId;
    }

    public void setSourceLocationId(UUID sourceLocationId) {
        this.sourceLocationId = sourceLocationId;
    }

    public UUID getDestinationLocationId() {
        return destinationLocationId;
    }

    public void setDestinationLocationId(UUID destinationLocationId) {
        this.destinationLocationId = destinationLocationId;
    }

    public List<CreateOperatorRouteStopRequest> getStops() {
        return stops;
    }

    public void setStops(List<CreateOperatorRouteStopRequest> stops) {
        this.stops = stops;
    }

    public List<RouteStopDefinitionRequest> toAdminStopDefinitions() {
        if (stops == null) {
            return null;
        }
        return stops.stream().map(CreateOperatorRouteStopRequest::toAdminDefinition).toList();
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
