package in.bluebustickets.bluebus.ticket.application;

public record TicketPdf(String ticketNumber, byte[] content) {

    public String filename() {
        return "BlueBus-Ticket-" + sanitizedTicketNumber() + ".pdf";
    }

    private String sanitizedTicketNumber() {
        String safe = ticketNumber == null ? "" : ticketNumber.replaceAll("[^A-Za-z0-9._-]", "");
        return safe.isBlank() ? "ticket" : safe;
    }
}
