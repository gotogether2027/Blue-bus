package in.bluebustickets.bluebus.foundation.demodata;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Stable names/dates that identify local-only demo catalog rows.
 * Not production master data.
 */
public final class DemoDataCatalog {

    public static final String LOCATION_LOCALITY = "BLUE BUS local demo";
    public static final String HYDERABAD_CITY = "Hyderabad";
    public static final String HYDERABAD_STATE = "Telangana";
    public static final String SURYAPET_CITY = "Suryapet";
    public static final String SURYAPET_STATE = "Telangana";
    public static final String VIJAYAWADA_CITY = "Vijayawada";
    public static final String VIJAYAWADA_STATE = "Andhra Pradesh";

    public static final String OPERATOR_LEGAL_NAME = "BLUE BUS Local Demo Operator";
    public static final String OPERATOR_DISPLAY_NAME = "BLUE BUS Demo";
    public static final String BUS_TYPE_CODE = "DEMO_SEATER";
    public static final String BUS_TYPE_NAME = "Demo Seater";
    public static final String LAYOUT_NAME = "BLUE BUS Local Demo Layout";
    public static final int LAYOUT_VERSION = 1;
    public static final String BUS_REGISTRATION = "TS09DEMO1";
    public static final String BUS_DISPLAY_NAME = "Demo Express";
    public static final String ROUTE_CODE = "DEMO-HYD-VJA";
    public static final String ROUTE_NAME = "Hyderabad to Vijayawada";

    public static final String DEFAULT_CUSTOMER_EMAIL = "demo.customer@example.test";
    public static final String CUSTOMER_FIRST_NAME = "Demo";
    public static final String CUSTOMER_LAST_NAME = "Customer";
    public static final String DEFAULT_OPERATOR_EMAIL = "demo.operator@example.test";
    public static final String OPERATOR_FIRST_NAME = "Demo";
    public static final String OPERATOR_LAST_NAME = "Operator";

    public static final String TIME_ZONE = "Asia/Kolkata";
    public static final Instant DEPARTURE_AT = Instant.parse("2099-01-15T12:30:00Z");
    public static final Instant ARRIVAL_AT = DEPARTURE_AT.plusSeconds(270L * 60L);
    public static final Instant BOOKING_OPENS_AT = Instant.parse("2020-01-01T00:00:00Z");
    public static final Instant BOOKING_CLOSES_AT = DEPARTURE_AT.minusSeconds(3600L);
    public static final LocalDate SERVICE_DATE = DEPARTURE_AT.atZone(ZoneId.of(TIME_ZONE)).toLocalDate();
    public static final BigDecimal BASE_FARE = new BigDecimal("900.00");

    private DemoDataCatalog() {
    }
}
