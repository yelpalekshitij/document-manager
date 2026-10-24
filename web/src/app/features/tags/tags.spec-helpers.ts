// Shared fixtures for the tag specs. Excluded from the app build via
// tsconfig.app.json (`*.spec-helpers.ts`); pulled into the Karma bundle by the
// specs that import it.
import { HttpErrorResponse } from '@angular/common/http';

import { DocumentsService, SearchService, TagsService } from '../../core/api/generated';
import { DocumentSummary, Tag, TagRef } from '../../core/api/types';

export function makeTag(name: string, extra: Partial<Tag> = {}): Tag {
  return { id: `id-${name}`, name, documentCount: 0, createdAt: '2026-01-01T00:00:00Z', ...extra };
}

export function ref(tag: Tag): TagRef {
  return { id: tag.id, name: tag.name, color: tag.color };
}

export function makeDoc(id: string, tags: TagRef[] = []): DocumentSummary {
  return {
    id,
    filename: `${id}.pdf`,
    mimeType: 'application/pdf',
    sizeBytes: 1024,
    status: 'READY',
    uploadedAt: '2026-01-01T00:00:00Z',
    tags,
  } as unknown as DocumentSummary;   // 'READY' literal vs the generated string enum
}

export function problem(status: number, slug: string, detail = 'Something went wrong'): HttpErrorResponse {
  return new HttpErrorResponse({
    status,
    error: { type: `https://vaultdoc.app/problems/${slug}`, title: 'Problem', status, detail },
  });
}

export function createTagsApiSpy(): jasmine.SpyObj<TagsService> {
  return jasmine.createSpyObj<TagsService>('TagsService', [
    'listTags',
    'createTag',
    'updateTag',
    'deleteTag',
    'setDocumentTags',
    'addDocumentTag',
    'removeDocumentTag',
  ]);
}

export function createDocumentsApiSpy(): jasmine.SpyObj<DocumentsService> {
  return jasmine.createSpyObj<DocumentsService>('DocumentsService', [
    'listDocuments',
    'getDocument',
    'getDocumentDownload',
    'deleteDocument',
    'initiateUpload',
    'finalizeUpload',
  ]);
}

export function createSearchApiSpy(): jasmine.SpyObj<SearchService> {
  return jasmine.createSpyObj<SearchService>('SearchService', ['searchDocuments']);
}
