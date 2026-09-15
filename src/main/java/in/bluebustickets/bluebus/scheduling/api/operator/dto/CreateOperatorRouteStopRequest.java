package in.bluebustickets.bluebus.scheduling.api.operator.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.RoutePointDefinitionRequest;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.RouteStopDefinitionRequest;
import in.bluebustickets.bluebus.scheduling.domain.StopKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class CreateOperatorRouteStopRequest {

    @NotNull
    private UUID locationId;

    @Min(1)
    private int sequenceNumber;

    @NotNull
    private StopKind stopKind;

    @Min(0)
    private Integer arrivalOffsetMinutes;

    @Min(0)
    private Integer departureOffsetMinutes;

    @DecimalMin("0.0")
    private BigDecimal distanceKm;

    @Valid
    private List<OperatorRoutePointDefinitionRequest> points;

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public StopKind getStopKind() {
        return stopKind;
    }

    public void setStopKind(StopKind stopKind) {
        this.stopKind = stopKind;
    }

    public Integer getArrivalOffsetMinutes() {
        return arrivalOffsetMinutes;
    }

    public void setArrivalOffsetMinutes(Integer arrivalOffsetMinutes) {
        this.arrivalOffsetMinutes = arrivalOffsetMinutes;
    }

    public Integer getDepartureOffsetMinutes() {
        return departureOffsetMinutes;
    }

    public void setDepartureOffsetMinutes(Integer departureOffsetMinutes) {
        this.departureOffsetMinutes = departureOffsetMinutes;
    }

    public BigDecimal getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(BigDecimal distanceKm) {
        this.distanceKm = distanceKm;
    }

    public List<OperatorRoutePointDefinitionRequest> getPoints() {
        return points;
    }

    public void setPoints(List<OperatorRoutePointDefinitionRequest> points) {
        this.points = points;
    }

    public RouteStopDefinitionRequest toAdminDefinition() {
        List<RoutePointDefinitionRequest> adminPoints = points == null
                ? null
                : points.stream().map(OperatorRoutePointDefinitionRequest::toAdminDefinition).toList();
        return new RouteStopDefinitionRequest(
                locationId,
                sequenceNumber,
                stopKind,
                arrivalOffsetMinutes,
                departureOffsetMinutes,
                distanceKm,
                adminPoints);
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
