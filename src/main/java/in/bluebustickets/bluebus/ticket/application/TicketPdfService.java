package in.bluebustickets.bluebus.ticket.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import in.bluebustickets.bluebus.ticket.api.dto.TicketPassengerResponse;
import in.bluebustickets.bluebus.ticket.api.dto.TicketResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Renders an official A4 e-ticket from the existing {@link TicketResponse} snapshot.
 * QR payload is the ticket number only — the same value the customer app encodes.
 */
@Component
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TicketPdfService {

    private static final DateTimeFormatter INSTANT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final java.awt.Color NAVY = new java.awt.Color(11, 61, 110);
    private static final java.awt.Color MUTED = new java.awt.Color(92, 107, 122);
    private static final java.awt.Color LINE = new java.awt.Color(217, 225, 234);

    public byte[] render(TicketResponse ticket) {
        Document document = new Document(PageSize.A4, 48, 48, 48, 52);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            addHeader(document);
            addSection(document, "TICKET INFORMATION");
            document.add(infoTable(new String[][]{
                    {"Ticket Number", text(ticket.ticketNumber())},
                    {"Booking Reference", text(ticket.bookingReference())},
                    {"Ticket Status", ticket.status() == null ? "—" : ticket.status().name()},
                    {"Issued At", formatInstant(ticket.issuedAt())}
            }));
            addSection(document, "JOURNEY");
            document.add(infoTable(new String[][]{
                    {"Origin", ticket.journey() == null ? "—" : text(ticket.journey().origin())},
                    {"Destination", ticket.journey() == null ? "—" : text(ticket.journey().destination())},
                    {"Departure", ticket.journey() == null ? "—" : formatInstant(ticket.journey().departure())},
                    {"Arrival", ticket.journey() == null ? "—" : formatInstant(ticket.journey().arrival())},
                    {"Operator", ticket.operator() == null ? "—" : text(ticket.operator().name())}
            }));
            addSection(document, "PASSENGERS");
            document.add(passengerTable(ticket));
            addSection(document, "PAYMENT");
            document.add(infoTable(new String[][]{
                    {"Total amount", formatMoney(ticket.amount(), ticket.currency())},
                    {"Currency", text(ticket.currency())}
            }));
            addQr(document, ticket.ticketNumber());
            addFooter(document);
            document.close();
        } catch (DocumentException | IOException exception) {
            throw new IllegalStateException("Ticket PDF could not be generated.", exception);
        }
        return out.toByteArray();
    }

    private static void addHeader(Document document) {
        Paragraph brand = new Paragraph("BLUE BUS", font(18, Font.BOLD, NAVY));
        brand.setSpacingAfter(2f);
        document.add(brand);
        Paragraph subtitle = new Paragraph("E-TICKET", font(12, Font.BOLD, NAVY));
        subtitle.setSpacingAfter(14f);
        document.add(subtitle);
    }

    private static void addSection(Document document, String title) {
        Paragraph heading = new Paragraph(title, font(9, Font.BOLD, NAVY));
        heading.setSpacingBefore(10f);
        heading.setSpacingAfter(6f);
        document.add(heading);
    }

    private static PdfPTable infoTable(String[][] rows) {
        PdfPTable table = new PdfPTable(new float[]{2.1f, 5f});
        table.setWidthPercentage(100);
        for (String[] row : rows) {
            table.addCell(labelCell(row[0]));
            table.addCell(valueCell(row[1]));
        }
        return table;
    }

    private static PdfPTable passengerTable(TicketResponse ticket) {
        PdfPTable table = new PdfPTable(new float[]{3.2f, 1.1f, 1.6f, 1.4f, 1.6f});
        table.setWidthPercentage(100);
        table.addCell(headerCell("Passenger name"));
        table.addCell(headerCell("Age"));
        table.addCell(headerCell("Gender"));
        table.addCell(headerCell("Seat number"));
        table.addCell(headerCell("Fare"));
        if (ticket.passengers() == null || ticket.passengers().isEmpty()) {
            PdfPCell empty = valueCell("No passengers on this ticket.");
            empty.setColspan(5);
            table.addCell(empty);
            return table;
        }
        for (TicketPassengerResponse passenger : ticket.passengers()) {
            table.addCell(valueCell(text(passenger.name())));
            table.addCell(valueCell(passenger.age() == null ? "—" : passenger.age().toString()));
            table.addCell(valueCell(text(passenger.gender())));
            table.addCell(valueCell(text(passenger.seat())));
            table.addCell(valueCell(formatMoney(passenger.fareAmount(), passenger.currency())));
        }
        return table;
    }

    private static void addQr(Document document, String ticketNumber) throws IOException {
        Paragraph heading = new Paragraph("QR CODE", font(9, Font.BOLD, NAVY));
        heading.setSpacingBefore(14f);
        heading.setSpacingAfter(6f);
        document.add(heading);
        Image qr = Image.getInstance(qrPng(ticketNumber));
        qr.scaleAbsolute(132, 132);
        qr.setAlignment(Element.ALIGN_LEFT);
        document.add(qr);
        Paragraph caption = new Paragraph("Scan to verify ticket  " + text(ticketNumber), font(8, Font.NORMAL, MUTED));
        caption.setSpacingBefore(4f);
        document.add(caption);
    }

    private static void addFooter(Document document) {
        Paragraph footer = new Paragraph(
                "This e-ticket is the official travel document for the journey shown above. "
                        + "Present this ticket and a valid photo ID when boarding. "
                        + "Schedules may change due to traffic, weather, or operations. "
                        + "Keep this document for your records.",
                font(8, Font.NORMAL, MUTED));
        footer.setSpacingBefore(22f);
        document.add(footer);
    }

    static byte[] qrPng(String ticketNumber) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(
                    ticketNumber,
                    BarcodeFormat.QR_CODE,
                    220,
                    220,
                    Map.of(
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            EncodeHintType.MARGIN, 1,
                            EncodeHintType.CHARACTER_SET, "UTF-8"));
            BufferedImage image = MatrixToImageWriter.toBufferedImage(
                    matrix, new MatrixToImageConfig(0xFF0B3D6E, 0xFFFFFFFF));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("Ticket QR PNG encoder is unavailable.");
            }
            return out.toByteArray();
        } catch (WriterException | IOException exception) {
            throw new IllegalStateException("Ticket QR could not be generated.", exception);
        }
    }

    private static PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(8, Font.BOLD, NAVY)));
        cell.setBackgroundColor(LINE);
        cell.setPadding(6f);
        cell.setBorderColor(LINE);
        return cell;
    }

    private static PdfPCell labelCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(8, Font.BOLD, MUTED)));
        cell.setPadding(6f);
        cell.setBorderColor(LINE);
        return cell;
    }

    private static PdfPCell valueCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(10, Font.NORMAL, java.awt.Color.BLACK)));
        cell.setPadding(6f);
        cell.setBorderColor(LINE);
        return cell;
    }

    private static Font font(float size, int style, java.awt.Color color) {
        Font font = FontFactory.getFont(FontFactory.HELVETICA, size, style, color);
        font.setStyle(style);
        return font;
    }

    private static String formatInstant(java.time.Instant instant) {
        return instant == null ? "—" : INSTANT.format(instant);
    }

    private static String formatMoney(BigDecimal amount, String currency) {
        String value = amount == null ? "—" : amount.toPlainString();
        String code = text(currency);
        return "—".equals(code) ? value : value + " " + code;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
