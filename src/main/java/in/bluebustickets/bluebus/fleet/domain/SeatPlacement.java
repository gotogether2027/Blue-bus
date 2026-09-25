package in.bluebustickets.bluebus.fleet.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Optional physical placement stored in {@code seats.attributes}.
 * Missing JSON fields keep the historical 1×1 forward seat.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SeatPlacement {

    public static final String FORWARD = "FORWARD";

    private static final ObjectMapper JSON = new ObjectMapper();

    private String orientation;
    private Integer spanColumns;
    private Integer spanRows;

    public SeatPlacement() {
    }

    public static SeatPlacement defaults() {
        SeatPlacement placement = new SeatPlacement();
        placement.orientation = FORWARD;
        placement.spanColumns = 1;
        placement.spanRows = 1;
        return placement;
    }

    public static SeatPlacement fromJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return defaults();
        }
        try {
            JsonNode node = JSON.readTree(json);
            SeatPlacement placement = new SeatPlacement();
            placement.orientation = text(node, "orientation");
            placement.spanRows = number(node, "spanRows");
            placement.spanColumns = number(node, "spanColumns");
            return placement;
        } catch (JsonProcessingException exception) {
            return defaults();
        }
    }

    public static String toJson(SeatPlacement placement) {
        SeatPlacement value = placement == null ? defaults() : placement;
        if (FORWARD.equals(value.getOrientation()) && value.getSpanRows() == 1 && value.getSpanColumns() == 1) {
            return "{}";
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Seat placement could not be stored");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.asInt();
    }

    public static SeatPlacement of(String orientation, Integer spanRows, Integer spanColumns) {
        SeatPlacement placement = new SeatPlacement();
        placement.orientation = orientation == null || orientation.isBlank() ? FORWARD : orientation.trim();
        placement.spanRows = spanRows == null ? 1 : spanRows;
        placement.spanColumns = spanColumns == null ? 1 : spanColumns;
        return placement;
    }

    public String getOrientation() {
        return orientation == null || orientation.isBlank() ? FORWARD : orientation;
    }

    public void setOrientation(String orientation) {
        this.orientation = orientation;
    }

    public int getSpanColumns() {
        return spanColumns == null || spanColumns < 1 ? 1 : spanColumns;
    }

    public void setSpanColumns(Integer spanColumns) {
        this.spanColumns = spanColumns;
    }

    public int getSpanRows() {
        return spanRows == null || spanRows < 1 ? 1 : spanRows;
    }

    public void setSpanRows(Integer spanRows) {
        this.spanRows = spanRows;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SeatPlacement placement)) {
            return false;
        }
        return getOrientation().equals(placement.getOrientation())
                && getSpanRows() == placement.getSpanRows()
                && getSpanColumns() == placement.getSpanColumns();
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(getOrientation(), getSpanRows(), getSpanColumns());
    }
}
