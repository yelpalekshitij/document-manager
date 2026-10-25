import { Component, computed, inject, input, output, signal } from '@angular/core';

import { DocumentsStore } from './documents.store';
import { DocumentDetail, DocumentSummary, TagRef } from '../../core/api/types';
import { TagChipsComponent } from '../tags/tag-chips.component';

@Component({
  selector: 'vd-document-viewer',
  standalone: true,
  imports: [TagChipsComponent],
  template: `
    <div class="overlay" (click)="dismiss($event)">
      <section class="panel" (click)="$event.stopPropagation()">
        <header>
          <h2>{{ doc()?.filename }}</h2>
          <button type="button" class="close" (click)="close.emit()" aria-label="Close">×</button>
        </header>

        <div class="tag-bar">
          <vd-tag-chips
            [documentId]="doc().id"
            [tags]="currentTags()"
            [editable]="true"
            (changed)="localTags.set($event)"
          />
        </div>

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
            @if (detail(); as d) {
              @if (d.ocrStatus === 'DONE') {
                @if (d.ocrText) {
                  <pre>{{ d.ocrText }}</pre>
                } @else {
                  <p class="muted">No text was found in this document.</p>
                }
                @if (d.ocrPageCount) {
                  <p class="muted">{{ d.ocrPageCount }} page(s)</p>
                }
                @if (d.ocrFailureReason) {
                  <!-- DONE but incomplete (page cap, time budget, a page that failed): say what's missing. -->
                  <p class="ocr-note" role="status">
                    <strong>Partly searchable.</strong> {{ d.ocrFailureReason }}
                  </p>
                }
              } @else if (d.ocrStatus === 'PROCESSING' || d.ocrStatus === 'PENDING') {
                <p class="muted">Extraction in progress — refresh in a moment.</p>
              } @else if (d.ocrStatus === 'SKIPPED') {
                <p class="muted">{{ d.ocrFailureReason ?? 'Text extraction is not available for this file type.' }}</p>
              } @else if (d.ocrStatus === 'FAILED') {
                <!-- The reason comes from the server (timeout, missing language pack, oversized image…). Never hide it. -->
                <p class="error" role="alert">
                  <strong>Text extraction failed.</strong>
                  @if (d.ocrFailureReason) {
                    <span class="reason">{{ d.ocrFailureReason }}</span>
                  }
                </p>
              }
            } @else if (error()) {
              <p class="error" role="alert">Couldn't load extraction details: {{ error() }}</p>
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
      .tag-bar { padding: 0.5rem 1.25rem; border-bottom: 1px solid var(--vd-border); }
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
      .ocr-body .error, .ocr-body .muted { padding: 0.5rem 0; text-align: left; }
      .ocr-body .reason { display: block; margin-top: 0.25rem; font-size: 0.8rem; }
      .ocr-note {
        margin: 0.5rem 0 0; padding: 0.4rem 0.6rem; font-size: 0.8rem;
        border-left: 3px solid var(--vd-warning, #c69026); color: var(--vd-fg);
      }
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
  /** Set from the chips' (changed) output — covers docs opened from search hits, which aren't in the list. */
  protected readonly localTags = signal<TagRef[] | null>(null);
  protected readonly currentTags = computed<TagRef[]>(() => {
    const id = this.doc().id;
    return this.store.documents().find((d) => d.id === id)?.tags
      ?? this.localTags()
      ?? this.detail()?.tags
      ?? this.doc().tags
      ?? [];
  });

  protected readonly isPdf = computed(() => this.doc()?.mimeType === 'application/pdf');
  protected readonly isImage = computed(() => (this.doc()?.mimeType ?? '').startsWith('image/'));
  // Open for DONE (show the text) and FAILED (never hide why extraction failed).
  protected readonly ocrPanelOpen = computed(() => {
    const status = this.detail()?.ocrStatus;
    return status === 'DONE' || status === 'FAILED';
  });
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
