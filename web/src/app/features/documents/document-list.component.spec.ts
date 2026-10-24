import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { DocumentListComponent } from './document-list.component';
import { DocumentsStore } from './documents.store';
import { DocumentSummary } from '../../core/api/types';

describe('DocumentListComponent', () => {
  const doc = (ocrStatus: string): DocumentSummary =>
    ({
      id: `id-${ocrStatus}`,
      filename: `${ocrStatus.toLowerCase()}.png`,
      mimeType: 'image/png',
      sizeBytes: 2048,
      status: 'READY',
      uploadedAt: '2026-10-24T10:00:00Z',
      ocrStatus,
    }) as unknown as DocumentSummary;

  function render(rows: DocumentSummary[]) {
    TestBed.configureTestingModule({
      imports: [DocumentListComponent],
      providers: [
        {
          provide: DocumentsStore,
          useValue: { documents: signal(rows), loading: signal(false), deleteDocument: jasmine.createSpy() },
        },
      ],
    });
    const fixture = TestBed.createComponent(DocumentListComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('OCR badge is labelled for screen readers', () => {
    const el = render([doc('FAILED'), doc('SKIPPED'), doc('DONE')]);
    const badges = Array.from(el.querySelectorAll<HTMLElement>('.ocr'));

    expect(badges.map((b) => b.getAttribute('role'))).toEqual(['img', 'img', 'img']);
    expect(badges.map((b) => b.getAttribute('aria-label'))).toEqual([
      'OCR failed — open the document to see why',
      'OCR not available for this file type',
      'Text extracted and searchable',
    ]);
    // Tooltip mirrors the accessible name for mouse users.
    expect(badges.map((b) => b.getAttribute('title'))).toEqual(badges.map((b) => b.getAttribute('aria-label')));
  });

  it('shows the empty state when there are no documents', () => {
    const el = render([]);
    expect(el.textContent).toContain('No documents yet');
  });
});
