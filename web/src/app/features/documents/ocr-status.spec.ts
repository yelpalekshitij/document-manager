import { ocrIcon, ocrLabel } from './ocr-status';

describe('ocr-status', () => {
  describe('ocrIcon', () => {
    it('maps every status to a glyph', () => {
      expect(ocrIcon('DONE')).toBe('✓');
      expect(ocrIcon('PROCESSING')).toBe('⏳');
      expect(ocrIcon('PENDING')).toBe('⏳');
      expect(ocrIcon('SKIPPED')).toBe('·');
      expect(ocrIcon('FAILED')).toBe('✗');
    });

    it('falls back to a dash for unknown / missing status', () => {
      expect(ocrIcon(undefined)).toBe('—');
      expect(ocrIcon(null)).toBe('—');
      expect(ocrIcon('SOMETHING_NEW')).toBe('—');
    });
  });

  describe('ocrLabel', () => {
    it('describes each status in plain words', () => {
      expect(ocrLabel('DONE')).toBe('Text extracted and searchable');
      expect(ocrLabel('PROCESSING')).toBe('OCR in progress');
      expect(ocrLabel('PENDING')).toBe('Awaiting OCR worker');
      expect(ocrLabel('FAILED')).toBe('OCR failed — open the document to see why');
      expect(ocrLabel(undefined)).toBe('OCR status unknown');
    });

    it('no longer promises Tesseract "later" for images', () => {
      expect(ocrLabel('SKIPPED')).toBe('OCR not available for this file type');
      for (const status of ['DONE', 'PROCESSING', 'PENDING', 'SKIPPED', 'FAILED']) {
        expect(ocrLabel(status)).not.toMatch(/1\.4|not yet supported/);
      }
    });
  });
});
