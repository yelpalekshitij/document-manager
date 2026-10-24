import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DocumentViewerComponent } from './document-viewer.component';
import { DocumentsStore } from './documents.store';
import { DocumentDetail, DocumentSummary } from '../../core/api/types';

describe('DocumentViewerComponent — extracted-text panel', () => {
  const summary = {
    id: 'doc-1',
    filename: 'scan.png',
    mimeType: 'image/png',
    sizeBytes: 1024,
    status: 'READY',
    uploadedAt: '2026-10-24T10:00:00Z',
  } as unknown as DocumentSummary;

  const detail = (overrides: Record<string, unknown>): DocumentDetail =>
    ({ ...summary, ...overrides }) as unknown as DocumentDetail;

  let fixture: ComponentFixture<DocumentViewerComponent>;

  async function render(getDetail: () => Promise<DocumentDetail>): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      imports: [DocumentViewerComponent],
      providers: [
        {
          provide: DocumentsStore,
          useValue: {
            getDownloadUrl: () => Promise.resolve('https://storage.test/scan.png'),
            getDetail,
          },
        },
      ],
    });
    fixture = TestBed.createComponent(DocumentViewerComponent);
    fixture.componentRef.setInput('doc', summary);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    // load() is kicked off from a microtask, then awaits two promises.
    for (let i = 0; i < 5; i++) await new Promise((r) => setTimeout(r));
    fixture.detectChanges();
  }

  const panel = (el: HTMLElement) => el.querySelector<HTMLDetailsElement>('details.ocr-panel')!;
  const body = (el: HTMLElement) => el.querySelector<HTMLElement>('.ocr-body')!;

  it('shows the failure reason for FAILED in an alert region, panel open', async () => {
    const el = await render(() =>
      Promise.resolve(detail({ ocrStatus: 'FAILED', ocrFailureReason: 'tesseract timed out after 60s' })),
    );
    await settle();

    const alert = body(el).querySelector('[role="alert"]');
    expect(alert).not.toBeNull();
    expect(alert!.textContent).toContain('tesseract timed out after 60s');
    expect(panel(el).open).toBeTrue();
  });

  it('falls back to generic copy when FAILED has no reason', async () => {
    const el = await render(() => Promise.resolve(detail({ ocrStatus: 'FAILED', ocrFailureReason: null })));
    await settle();

    expect(body(el).querySelector('[role="alert"]')!.textContent).toContain('Text extraction failed.');
  });

  it('shows the skip reason for SKIPPED', async () => {
    const el = await render(() =>
      Promise.resolve(
        detail({
          ocrStatus: 'SKIPPED',
          ocrFailureReason: 'OCR is not supported for image/heic (supported: PDF, JPEG, PNG, TIFF, WebP)',
        }),
      ),
    );
    await settle();

    expect(body(el).textContent).toContain('OCR is not supported for image/heic');
    expect(body(el).textContent).not.toContain('1.4');
  });

  it('DONE with empty text shows no-text copy', async () => {
    const el = await render(() => Promise.resolve(detail({ ocrStatus: 'DONE', ocrText: '', ocrPageCount: 1 })));
    await settle();

    expect(body(el).textContent).toContain('No text was found in this document.');
    expect(body(el).textContent).not.toContain('Loading details');
  });

  it('DONE with text renders it', async () => {
    const el = await render(() =>
      Promise.resolve(detail({ ocrStatus: 'DONE', ocrText: 'भारत गणराज्य PASSPORT', ocrPageCount: 2 })),
    );
    await settle();

    expect(body(el).querySelector('pre')!.textContent).toBe('भारत गणराज्य PASSPORT');
    expect(body(el).textContent).toContain('2 page(s)');
  });

  it('shows loading then in-progress copy', async () => {
    let resolve!: (d: DocumentDetail) => void;
    const el = await render(() => new Promise<DocumentDetail>((r) => (resolve = r)));
    expect(body(el).textContent).toContain('Loading details…');

    resolve(detail({ ocrStatus: 'PROCESSING' }));
    await settle();
    expect(body(el).textContent).toContain('Extraction in progress');
  });

  it('surfaces a fetch error instead of loading forever', async () => {
    const el = await render(() => Promise.reject(new Error('Network down')));
    await settle();

    expect(body(el).textContent).not.toContain('Loading details…');
    expect(body(el).querySelector('[role="alert"]')!.textContent).toContain("Couldn't load extraction details");
  });
});
