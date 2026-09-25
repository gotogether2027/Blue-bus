import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  OperatorSeatLayoutType,
  OperatorSeatMarkerType,
  OperatorSeatOrientation,
  OperatorSeatType
} from '../../models/operator.models';
import { SeatLayoutCanvasComponent } from '../../seat-layout/seat-layout-canvas.component';
import {
  LayoutDraft,
  LayoutDraftSeat,
  LayoutTool,
  anchorAt,
  cellKey,
  draftFromLayout,
  placeTool,
  removeSeat,
  summaryOf,
  toCreateRequest,
  updateSeat,
  validateDraft,
  autoNumber
} from '../../seat-layout/seat-layout-draft';
import { SEAT_LAYOUT_TEMPLATES } from '../../seat-layout/seat-layout-templates';
import { OperatorApiService } from '../../services/operator-api.service';
import { OperatorContextService } from '../../services/operator-context.service';
import { OperatorErrorService } from '../../services/operator-error.service';

@Component({
  selector: 'app-operator-seat-layout-editor-page',
  imports: [FormsModule, RouterLink, SeatLayoutCanvasComponent],
  templateUrl: './operator-seat-layout-editor.page.html',
  styleUrl: './operator-seat-layout-editor.page.scss'
})
export class OperatorSeatLayoutEditorPageComponent implements OnInit {
  private readonly api = inject(OperatorApiService);
  readonly context = inject(OperatorContextService);
  private readonly errors = inject(OperatorErrorService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly templates = SEAT_LAYOUT_TEMPLATES;
  readonly seatTools: OperatorSeatType[] = ['SEATER', 'SLEEPER', 'SLEEPER_LOWER', 'SLEEPER_UPPER', 'BERTH'];
  readonly markerTools: OperatorSeatMarkerType[] = ['AISLE', 'EMPTY', 'DOOR', 'DRIVER', 'TOILET', 'UTILITY', 'BLOCKED'];
  readonly orientations: OperatorSeatOrientation[] = ['FORWARD', 'BACKWARD', 'HORIZONTAL', 'VERTICAL'];
  readonly layoutTypes: OperatorSeatLayoutType[] = ['SEATER', 'SLEEPER', 'SEATER_SLEEPER', 'CUSTOM'];

  loading = false;
  saving = false;
  message = '';
  deck = 1;
  tool: LayoutTool = 'SEATER';
  selectedKey = '';
  draft: LayoutDraft = this.templates[0].build();
  readonly summary = () => summaryOf(this.draft);
  readonly problems = () => validateDraft(this.draft);

  get operatorId(): string {
    return this.context.selectedOperatorId() ?? '';
  }

  get layoutId(): string | null {
    return this.route.snapshot.paramMap.get('layoutId');
  }

  get editing(): boolean {
    return !!this.layoutId;
  }

  get selectedSeat(): LayoutDraftSeat | null {
    const [deck, row, column] = this.selectedKey.split(':').map(Number);
    if (!deck) {
      return null;
    }
    return anchorAt(this.draft, deck, row, column);
  }

  ngOnInit(): void {
    const layoutId = this.layoutId;
    if (!layoutId) {
      return;
    }
    this.loading = true;
    this.api.getSeatLayout(this.operatorId, layoutId).subscribe({
      next: (layout) => {
        if (layout.status !== 'DRAFT') {
          void this.router.navigate(['/operator', this.operatorId, 'seat-layouts', layout.id]);
          return;
        }
        this.draft = draftFromLayout(layout);
        this.loading = false;
      },
      error: (error: unknown) => {
        this.message = this.errors.handle(error).message;
        this.loading = false;
      }
    });
  }

  applyTemplate(id: string): void {
    const template = this.templates.find((item) => item.id === id);
    if (!template) {
      return;
    }
    const name = this.draft.name;
    const built = template.build();
    this.draft = { ...built, name: name || built.name };
    this.deck = 1;
    this.selectedKey = '';
  }

  selectCell(cell: { deck: number; row: number; column: number }): void {
    const existing = anchorAt(this.draft, cell.deck, cell.row, cell.column);
    if (existing && this.tool !== 'ERASE') {
      this.selectedKey = cellKey(existing.deckNumber, existing.rowNumber, existing.columnNumber);
      return;
    }
    this.draft = placeTool(this.draft, this.tool, cell.deck, cell.row, cell.column);
    this.selectedKey = this.tool === 'ERASE' ? '' : cellKey(cell.deck, cell.row, cell.column);
  }

  changeSelected(patch: Partial<LayoutDraftSeat>): void {
    const current = this.selectedSeat;
    if (!current) {
      return;
    }
    const next = { ...current, ...patch };
    this.draft = updateSeat(this.draft, current.seatNumber, next);
    this.selectedKey = cellKey(next.deckNumber, next.rowNumber, next.columnNumber);
  }

  deleteSelected(): void {
    const current = this.selectedSeat;
    if (!current) {
      return;
    }
    this.draft = removeSeat(this.draft, current.seatNumber);
    this.selectedKey = '';
  }

  renumber(): void {
    this.draft = autoNumber(this.draft);
  }

  save(): void {
    const errors = validateDraft(this.draft);
    if (errors.length > 0) {
      this.message = errors[0];
      return;
    }
    this.saving = true;
    this.message = '';
    const request = toCreateRequest(this.draft);
    const operatorId = this.operatorId;
    const layoutId = this.layoutId;
    const call = layoutId
      ? this.api.updateSeatLayout(operatorId, layoutId, request)
      : this.api.createSeatLayout(operatorId, request);
    call.subscribe({
      next: (layout) => {
        this.saving = false;
        void this.router.navigate(['/operator', operatorId, 'seat-layouts', layout.id]);
      },
      error: (error: unknown) => {
        this.saving = false;
        this.message = this.errors.handle(error).message;
      }
    });
  }
}
