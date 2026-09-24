import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EMPTY, catchError, switchMap } from 'rxjs';
import QRCode from 'qrcode';
import { TicketsService } from '../../core/api/tickets.service';
import { Ticket } from '../../core/api/models';
import { readPdfDownloadError, savePdfBlob, ticketPdfFilename } from '../../core/api/ticket-pdf';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';
import { formatInstant, formatMoney } from '../../shared/format';
import { badgeTone } from '../bookings/booking-status';

@Component({
  selector: 'app-ticket-page',
  imports: [RouterLink, EmptyStateComponent, StatusBadgeComponent],
  templateUrl: './ticket.page.html',
  styleUrl: './ticket.page.scss'
})
export class TicketPageComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly ticketsApi = inject(TicketsService);
  private readonly destroyRef = inject(DestroyRef);
  private destroyed = false;

  bookingId = '';
  loading = true;
  notFound = false;
  error = false;
  ticket: Ticket | null = null;
  qrDataUrl = '';
  downloading = false;
  downloadError = '';

  readonly formatInstant = formatInstant;
  readonly formatMoney = formatMoney;
  readonly badgeTone = badgeTone;

  ngOnInit(): void {
    this.destroyRef.onDestroy(() => {
      this.destroyed = true;
    });

    this.route.paramMap
      .pipe(
        switchMap((params) => {
          const bookingId = params.get('bookingId');
          this.bookingId = bookingId ?? '';
          this.ticket = null;
          this.qrDataUrl = '';
          this.notFound = false;
          this.error = false;
          this.downloading = false;
          this.downloadError = '';
          if (!bookingId) {
            this.loading = false;
            this.error = true;
            return EMPTY;
          }
          this.loading = true;
          return this.ticketsApi.getByBooking(bookingId).pipe(
            catchError((err) => {
              this.loading = false;
              this.ticket = null;
              if (err instanceof HttpErrorResponse && err.status === 404) {
                this.notFound = true;
              } else {
                this.error = true;
              }
              return EMPTY;
            })
          );
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((ticket) => {
        this.ticket = ticket;
        this.loading = false;
        this.renderQr(ticket.ticketNumber);
      });
  }

  printTicket(): void {
    window.print();
  }

  downloadPdf(): void {
    if (!this.bookingId || this.downloading) {
      return;
    }
    this.downloading = true;
    this.downloadError = '';
    this.ticketsApi.downloadPdf(this.bookingId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (blob) => {
        this.downloading = false;
        savePdfBlob(blob, ticketPdfFilename(this.ticket?.ticketNumber, this.bookingId));
      },
      error: (err) => {
        this.downloading = false;
        this.downloadError = readPdfDownloadError(err);
      }
    });
  }

  private renderQr(ticketNumber: string): void {
    void QRCode.toDataURL(ticketNumber, {
      width: 220,
      margin: 1,
      errorCorrectionLevel: 'M',
      color: { dark: '#0b3d6e', light: '#ffffff' }
    }).then(
      (url) => {
        if (!this.destroyed) {
          this.qrDataUrl = url;
        }
      },
      () => {
        if (!this.destroyed) {
          this.qrDataUrl = '';
        }
      }
    );
  }
}
