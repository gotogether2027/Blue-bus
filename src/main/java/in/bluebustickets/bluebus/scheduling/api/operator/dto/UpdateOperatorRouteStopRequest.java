package in.bluebustickets.bluebus.scheduling.api.operator.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import in.bluebustickets.bluebus.scheduling.domain.StopKind;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class UpdateOperatorRouteStopRequest {

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

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
