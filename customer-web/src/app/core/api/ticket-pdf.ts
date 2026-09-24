import { HttpErrorResponse } from '@angular/common/http';

export function ticketPdfFilename(ticketNumber: string | null | undefined, bookingId: string): string {
  return `BlueBus-Ticket-${ticketNumber || bookingId}.pdf`;
}

export function savePdfBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.rel = 'noopener';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

export function readPdfDownloadError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 401) {
      return 'Please sign in to download this ticket.';
    }
    if (error.status === 404) {
      return 'Ticket PDF is not available.';
    }
  }
  return 'Unable to download the PDF ticket. Please try again.';
}
