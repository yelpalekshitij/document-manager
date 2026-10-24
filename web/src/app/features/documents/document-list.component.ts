import { Component, inject, output } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';

import { DocumentsStore } from './documents.store';
import { DocumentSummary } from '../../core/api/types';
import { TagChipsComponent } from '../tags/tag-chips.component';

@Component({
  selector: 'vd-document-list',
  standalone: true,
  imports: [DatePipe, DecimalPipe, TagChipsComponent],
  template: `
    @if (store.loading()) {
      <p class="muted">Loading…</p>
    } @else if (rows().length === 0) {
      @if (store.selectedTagIds().length > 0) {
        <p class="muted">No documents carry all of the selected tags.</p>
      } @else {
        <p class="muted">No documents yet. Drop a PDF or image into the zone above to get started.</p>
      }
    } @else {
      <table>
        <thead>
          <tr>
            <th>Filename</th>
            <th>Tags</th>
            <th>Type</th>
            <th>OCR</th>
            <th>Size</th>
            <th>Uploaded</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          @for (doc of rows(); track doc.id) {
            <tr (click)="opened.emit(doc)">
              <td class="name">{{ doc.filename }}</td>
              <td class="tags">
                <vd-tag-chips [documentId]="doc.id" [tags]="doc.tags ?? []" [editable]="true" />
              </td>
              <td><span class="badge">{{ shortType(doc.mimeType) }}</span></td>
              <td>
                <span class="ocr" [title]="ocrTooltip(doc.ocrStatus)">{{ ocrIcon(doc.ocrStatus) }}</span>
              </td>
              <td>{{ (doc.sizeBytes / 1024) | number: '1.0-1' }} KB</td>
              <td>{{ doc.uploadedAt | date: 'medium' }}</td>
              <td class="actions">
                <button type="button" (click)="onDelete($event, doc)">Delete</button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
  styles: [
    `
      table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
      th, td {
        text-align: left;
        padding: 0.6rem 0.75rem;
        border-bottom: 1px solid var(--vd-border);
      }
      th { color: var(--vd-fg-muted); font-weight: 500; font-size: 0.78rem; text-transform: uppercase; }
      tbody tr { cursor: pointer; }
      tbody tr:hover { background: rgba(255, 255, 255, 0.02); }
      .name { font-weight: 500; }
      .tags { min-width: 10rem; }
      .badge {
        font-size: 0.72rem;
        color: var(--vd-fg-muted);
        background: rgba(255, 255, 255, 0.04);
        padding: 0.1rem 0.4rem;
        border-radius: 4px;
      }
      .actions { text-align: right; }
      .actions button {
        background: transparent;
        border: 1px solid var(--vd-border);
        color: var(--vd-danger);
        font: inherit;
        padding: 0.25rem 0.6rem;
        border-radius: 5px;
        cursor: pointer;
      }
      .actions button:hover { border-color: var(--vd-danger); }
      .muted { color: var(--vd-fg-muted); }
      .ocr { font-size: 0.95rem; cursor: help; }
    `,
  ],
})
export class DocumentListComponent {
  readonly opened = output<DocumentSummary>();

  protected readonly store = inject(DocumentsStore);

  protected rows = () => this.store.documents();

  protected shortType(mime: string | undefined): string {
    if (!mime) return '—';
    if (mime === 'application/pdf') return 'PDF';
    if (mime.startsWith('image/')) return mime.replace('image/', '').toUpperCase();
    if (mime.includes('officedocument.wordprocessingml')) return 'DOCX';
    return mime;
  }

  protected ocrIcon(status: string | null | undefined): string {
    switch (status) {
      case 'DONE': return '✓';
      case 'PROCESSING': return '⏳';
      case 'PENDING': return '⏳';
      case 'SKIPPED': return '·';
      case 'FAILED': return '✗';
      default: return '—';
    }
  }

  protected ocrTooltip(status: string | null | undefined): string {
    switch (status) {
      case 'DONE': return 'Text extracted and searchable';
      case 'PROCESSING': return 'OCR in progress';
      case 'PENDING': return 'Awaiting OCR worker';
      case 'SKIPPED': return 'OCR skipped (not yet supported for this type)';
      case 'FAILED': return 'OCR failed — open the doc for the reason';
      default: return 'OCR status unknown';
    }
  }

  async onDelete(event: MouseEvent, doc: DocumentSummary): Promise<void> {
    event.stopPropagation();
    if (!confirm(`Delete "${doc.filename}"? This cannot be undone.`)) return;
    if (doc.id) await this.store.deleteDocument(doc.id);
  }
}
