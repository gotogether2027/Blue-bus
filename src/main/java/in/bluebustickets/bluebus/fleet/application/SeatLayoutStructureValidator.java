package in.bluebustickets.bluebus.fleet.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import in.bluebustickets.bluebus.fleet.api.admin.dto.SeatDefinitionRequest;
import in.bluebustickets.bluebus.fleet.api.admin.dto.SeatLayoutMarkerRequest;
import in.bluebustickets.bluebus.fleet.domain.SeatLayoutMarker;
import in.bluebustickets.bluebus.fleet.domain.SeatLayoutType;
import in.bluebustickets.bluebus.fleet.domain.SeatPlacement;

/**
 * Shared position rules for operator and platform seat-layout writes.
 * Duplicate seat numbers and positions are illegal arguments; the platform
 * service maps those to conflicts.
 */
final class SeatLayoutStructureValidator {

    static final Set<String> SEAT_TYPES = Set.of(
            "SEATER", "SLEEPER", "SLEEPER_LOWER", "SLEEPER_UPPER", "BERTH");
    static final Set<String> ORIENTATIONS = Set.of("FORWARD", "BACKWARD", "HORIZONTAL", "VERTICAL");
    static final Set<String> MARKER_TYPES = Set.of(
            "AISLE", "EMPTY", "DOOR", "DRIVER", "TOILET", "UTILITY", "BLOCKED");

    private SeatLayoutStructureValidator() {
    }

    static SeatLayoutType layoutTypeOrCustom(String value) {
        if (value == null || value.isBlank()) {
            return SeatLayoutType.CUSTOM;
        }
        return SeatLayoutType.parse(value);
    }

    static List<SeatLayoutMarker> markersFrom(List<SeatLayoutMarkerRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<SeatLayoutMarker> markers = new ArrayList<>(requests.size());
        for (SeatLayoutMarkerRequest request : requests) {
            markers.add(new SeatLayoutMarker(
                    request.type().trim(),
                    request.deckNumber(),
                    request.rowNumber(),
                    request.columnNumber()));
        }
        return markers;
    }

    static void validate(
            int deckCount,
            int rowCount,
            int columnCount,
            List<SeatDefinitionRequest> seats,
            List<SeatLayoutMarkerRequest> markers) {
        Set<String> numbers = new HashSet<>();
        Set<String> occupied = new HashSet<>();
        if (seats != null) {
            for (SeatDefinitionRequest seat : seats) {
                occupySeat(deckCount, rowCount, columnCount, seat, numbers, occupied);
            }
        }
        if (markers != null) {
            for (SeatLayoutMarkerRequest marker : markers) {
                occupyMarker(deckCount, rowCount, columnCount, marker.type(), marker.deckNumber(),
                        marker.rowNumber(), marker.columnNumber(), occupied);
            }
        }
    }

    static void validateMarkers(
            int deckCount,
            int rowCount,
            int columnCount,
            List<SeatLayoutMarker> markers,
            Set<String> occupied) {
        if (markers == null) {
            return;
        }
        for (SeatLayoutMarker marker : markers) {
            occupyMarker(deckCount, rowCount, columnCount, marker.getType(), marker.getDeckNumber(),
                    marker.getRowNumber(), marker.getColumnNumber(), occupied);
        }
    }

    private static void occupySeat(
            int deckCount,
            int rowCount,
            int columnCount,
            SeatDefinitionRequest seat,
            Set<String> numbers,
            Set<String> occupied) {
        String number = requireText(seat.seatNumber(), "Seat number is required");
        String seatType = requireText(seat.seatType(), "Seat type is required");
        if (!SEAT_TYPES.contains(seatType)) {
            throw new IllegalArgumentException("Invalid seat type.");
        }
        int deck = requirePositive(seat.deckNumber(), "Seat deck number must be positive");
        int row = requirePositive(seat.rowNumber(), "Seat row number must be positive");
        int column = requirePositive(seat.columnNumber(), "Seat column number must be positive");
        int spanRows = span(seat.spanRows(), "Seat span must be positive.");
        int spanColumns = span(seat.spanColumns(), "Seat span must be positive.");
        String orientation = seat.orientation() == null || seat.orientation().isBlank()
                ? SeatPlacement.FORWARD
                : seat.orientation().trim();
        if (!ORIENTATIONS.contains(orientation)) {
            throw new IllegalArgumentException("Invalid seat orientation.");
        }
        if (deck > deckCount) {
            throw new IllegalArgumentException("Seat position is outside the layout dimensions for seat " + number);
        }
        if (!numbers.add(number.toLowerCase())) {
            throw new IllegalArgumentException("Duplicate seat number within layout: " + number);
        }
        for (int rowOffset = 0; rowOffset < spanRows; rowOffset++) {
            for (int columnOffset = 0; columnOffset < spanColumns; columnOffset++) {
                int occupiedRow = row + rowOffset;
                int occupiedColumn = column + columnOffset;
                if (occupiedRow > rowCount || occupiedColumn > columnCount) {
                    throw new IllegalArgumentException(
                            "Seat span is outside the layout dimensions for seat " + number);
                }
                String cell = deck + ":" + occupiedRow + ":" + occupiedColumn;
                if (!occupied.add(cell)) {
                    throw new IllegalArgumentException("Seat positions overlap within layout: " + cell);
                }
            }
        }
    }

    private static void occupyMarker(
            int deckCount,
            int rowCount,
            int columnCount,
            String type,
            int deck,
            int row,
            int column,
            Set<String> occupied) {
        String markerType = requireText(type, "Marker type is required");
        if (!MARKER_TYPES.contains(markerType)) {
            throw new IllegalArgumentException("Invalid marker type.");
        }
        requirePositive(deck, "Marker deck number must be positive");
        requirePositive(row, "Marker row number must be positive");
        requirePositive(column, "Marker column number must be positive");
        if (deck > deckCount || row > rowCount || column > columnCount) {
            throw new IllegalArgumentException("Marker position is outside the layout dimensions.");
        }
        String cell = deck + ":" + row + ":" + column;
        if (!occupied.add(cell)) {
            throw new IllegalArgumentException("Seat positions overlap within layout: " + cell);
        }
    }

    private static int span(Integer value, String message) {
        if (value == null) {
            return 1;
        }
        if (value < 1) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static int requirePositive(int value, String message) {
        if (value < 1) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
