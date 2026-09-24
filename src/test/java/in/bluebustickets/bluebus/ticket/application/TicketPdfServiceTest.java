package in.bluebustickets.bluebus.ticket.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import in.bluebustickets.bluebus.ticket.api.dto.TicketJourneyResponse;
import in.bluebustickets.bluebus.ticket.api.dto.TicketOperatorResponse;
import in.bluebustickets.bluebus.ticket.api.dto.TicketPassengerResponse;
import in.bluebustickets.bluebus.ticket.api.dto.TicketResponse;
import in.bluebustickets.bluebus.ticket.domain.TicketStatus;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TicketPdfServiceTest {

    private final TicketPdfService service = new TicketPdfService();

    @Test
    void rendersOfficialTicketWithPassengersAndQrPayload() throws Exception {
        TicketResponse ticket = sample(TicketStatus.ACTIVE);
        byte[] pdf = service.render(ticket);

        assertThat(pdf).startsWith("%PDF".getBytes());
        String text = TicketPdfAssertions.pdfText(pdf);
        assertThat(text).contains("BLUE BUS");
        assertThat(text).contains("E-TICKET");
        assertThat(text).contains("BBTEST01");
        assertThat(text).contains("BB-REF-1");
        assertThat(text).contains("ACTIVE");
        assertThat(text).contains("Hyderabad");
        assertThat(text).contains("Vijayawada");
        assertThat(text).contains("Coastal Travels");
        assertThat(text).contains("Asha Rao");
        assertThat(text).contains("Ravi Kumar");
        assertThat(text).contains("U1");
        assertThat(text).contains("U2");
        assertThat(text).contains("1299.00");
        assertThat(text).contains("INR");
        assertThat(text).doesNotContain("accessToken");
        assertThat(text).doesNotContain("refreshToken");
        assertThat(TicketPdfAssertions.decodeQr(pdf)).isEqualTo("BBTEST01");
    }

    @Test
    void cancelledTicketUsesExistingStatus() throws Exception {
        byte[] pdf = service.render(sample(TicketStatus.CANCELLED));
        assertThat(TicketPdfAssertions.pdfText(pdf)).contains("CANCELLED");
    }

    @Test
    void qrImageEncodesTicketNumberOnly() throws Exception {
        byte[] png = TicketPdfService.qrPng("BBTEST01");
        var image = ImageIO.read(new ByteArrayInputStream(png));
        String payload = new MultiFormatReader()
                .decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))))
                .getText();
        assertThat(payload).isEqualTo("BBTEST01");
    }

    @Test
    void filenameUsesTicketNumber() {
        assertThat(new TicketPdf("BBTEST01", new byte[]{1}).filename())
                .isEqualTo("BlueBus-Ticket-BBTEST01.pdf");
    }

    private static TicketResponse sample(TicketStatus status) {
        return new TicketResponse(
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                "BBTEST01",
                status,
                Instant.parse("2026-09-17T10:20:00Z"),
                "BB-REF-1",
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                new TicketOperatorResponse("Coastal Travels"),
                new TicketJourneyResponse(
                        "Hyderabad",
                        "Vijayawada",
                        Instant.parse("2026-12-01T10:00:00Z"),
                        Instant.parse("2026-12-01T16:00:00Z")),
                List.of(
                        new TicketPassengerResponse("Asha Rao", 32, "FEMALE", "U1",
                                new BigDecimal("650.00"), "INR"),
                        new TicketPassengerResponse("Ravi Kumar", 28, "MALE", "U2",
                                new BigDecimal("649.00"), "INR")),
                new BigDecimal("1299.00"),
                "INR");
    }
}
