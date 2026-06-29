import { Component, computed, inject, input, output, signal } from '@angular/core';

import { DocumentsStore } from './documents.store';
import { DocumentDetail, DocumentSummary } from '../../core/api/types';

@Component({
  selector: 'vd-document-viewer',
  standalone: true,
  template: `
    <div class="overlay" (click)="dismiss($event)">
      <section class="panel" (click)="$event.stopPropagation()">
        <header>
          <h2>{{ doc()?.filename }}</h2>
          <button type="button" class="close" (click)="close.emit()" aria-label="Close">×</button>
        </header>

        <div class="preview">
          @if (downloadUrl() && isPdf()) {
            <iframe [src]="safeUrl()" title="Document preview"></iframe>
          } @else if (downloadUrl() && isImage()) {
            <img [src]="downloadUrl()" [alt]="doc()?.filename" />
          } @else if (downloadUrl()) {
            <div class="fallback">
              <p>Preview not available for this file type.</p>
              <a [href]="downloadUrl()" target="_blank" rel="noopener noreferrer">Download</a>
            </div>
          } @else if (error()) {
            <p class="error">{{ error() }}</p>
          } @else {
            <p class="muted">Generating preview…</p>
          }
        </div>

        <details class="ocr-panel" [open]="ocrPanelOpen()">
          <summary>
            <span>Extracted text</span>
            <span class="status">{{ ocrSummaryLabel() }}</span>
          </summary>
          <div class="ocr-body">
            @if (detail()?.ocrStatus === 'DONE' && detail()?.ocrText) {
              <pre>{{ detail()?.ocrText }}</pre>
              @if (detail()?.ocrPageCount) {
                <p class="muted">{{ detail()?.ocrPageCount }} page(s)</p>
              }
            } @else if (detail()?.ocrStatus === 'PROCESSING' || detail()?.ocrStatus === 'PENDING') {
              <p class="muted">Extraction in progress — refresh in a moment.</p>
            } @else if (detail()?.ocrStatus === 'SKIPPED') {
              <p class="muted">No extractable text for this file type yet (Tesseract for images lands in 1.4).</p>
            } @else if (detail()?.ocrStatus === 'FAILED') {
              <p class="error">{{ detail()?.ocrFailureReason ?? 'Extraction failed.' }}</p>
            } @else {
              <p class="muted">Loading details…</p>
            }
          </div>
        </details>
      </section>
    </div>
  `,
  styles: [
    `
      .overlay {
        position: fixed; inset: 0;
        background: rgba(0, 0, 0, 0.55);
        display: flex; align-items: center; justify-content: center;
        z-index: 100;
      }
      .panel {
        background: var(--vd-bg-card);
        border: 1px solid var(--vd-border);
        border-radius: 12px;
        width: min(96vw, 1100px);
        height: min(92vh, 900px);
        display: flex; flex-direction: column;
        overflow: hidden;
      }
      header {
        display: flex; align-items: center; justify-content: space-between;
        padding: 0.75rem 1.25rem;
        border-bottom: 1px solid var(--vd-border);
      }
      h2 { margin: 0; font-size: 1rem; font-weight: 500; }
      .close {
        background: transparent; border: none; color: var(--vd-fg);
        font-size: 1.5rem; cursor: pointer; line-height: 1;
      }
      .preview { flex: 1; display: flex; min-height: 0; }
      iframe { flex: 1; border: none; background: #fff; }
      img { flex: 1; object-fit: contain; padding: 1rem; }
      .fallback, .muted, .error {
        padding: 2rem; text-align: center;
        color: var(--vd-fg-muted);
      }
      .fallback a { color: var(--vd-accent); }
      .error { color: var(--vd-danger); }
      .ocr-panel {
        border-top: 1px solid var(--vd-border);
        max-height: 35%;
        overflow: auto;
        background: var(--vd-bg);
      }
      .ocr-panel summary {
        cursor: pointer;
        padding: 0.6rem 1.25rem;
        font-size: 0.85rem;
        color: var(--vd-fg-muted);
        display: flex;
        justify-content: space-between;
        align-items: center;
      }
      .ocr-panel summary .status {
        font-size: 0.72rem;
        text-transform: uppercase;
        letter-spacing: 0.04em;
      }
      .ocr-body { padding: 0 1.25rem 1rem; }
      .ocr-body pre {
        white-space: pre-wrap;
        word-break: break-word;
        font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
        font-size: 0.78rem;
        color: var(--vd-fg);
        margin: 0;
      }
    `,
  ],
})
export class DocumentViewerComponent {
  readonly doc = input.required<DocumentSummary>();
  readonly close = output<void>();

  private readonly store = inject(DocumentsStore);

  protected readonly downloadUrl = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly detail = signal<DocumentDetail | null>(null);

  protected readonly isPdf = computed(() => this.doc()?.mimeType === 'application/pdf');
  protected readonly isImage = computed(() => (this.doc()?.mimeType ?? '').startsWith('image/'));
  protected readonly ocrPanelOpen = computed(() => this.detail()?.ocrStatus === 'DONE');
  protected readonly ocrSummaryLabel = computed(() => this.detail()?.ocrStatus ?? '—');

  // Plain string binding is fine for our same-origin presigned URLs;
  // Angular's URL sanitizer trips on the query string of a presigned URL
  // when used inside <iframe src>, so we use the unsafe-on-purpose attribute.
  protected safeUrl() {
    return this.downloadUrl();
  }

  constructor() {
    queueMicrotask(() => void this.load());
  }

  dismiss(event: MouseEvent): void {
    if (event.target === event.currentTarget) {
      this.close.emit();
    }
  }

  private async load(): Promise<void> {
    const d = this.doc();
    if (!d?.id) return;
    try {
      const [url, detail] = await Promise.all([
        this.store.getDownloadUrl(d.id),
        this.store.getDetail(d.id),
      ]);
      this.downloadUrl.set(url);
      this.detail.set(detail);
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : 'Failed to fetch preview');
    }
  }
}
