// OCR badge copy shared by the list (badge + tooltip + aria-label) and viewer.
// Kept as plain functions so the copy is unit-testable without a component.

export function ocrIcon(status: string | null | undefined): string {
  switch (status) {
    case 'DONE': return '✓';
    case 'PROCESSING': return '⏳';
    case 'PENDING': return '⏳';
    case 'SKIPPED': return '·';
    case 'FAILED': return '✗';
    default: return '—';
  }
}

/** Accessible name + tooltip for the badge. The FAILED/SKIPPED reason lives in the viewer. */
export function ocrLabel(status: string | null | undefined): string {
  switch (status) {
    case 'DONE': return 'Text extracted and searchable';
    case 'PROCESSING': return 'OCR in progress';
    case 'PENDING': return 'Awaiting OCR worker';
    case 'SKIPPED': return 'OCR not available for this file type';
    case 'FAILED': return 'OCR failed — open the document to see why';
    default: return 'OCR status unknown';
  }
}
