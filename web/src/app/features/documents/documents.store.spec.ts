import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { DocumentsService, FoldersService, SearchService, TagsService } from '../../core/api/generated';
import { DocumentsStore } from './documents.store';
import { TagsStore } from '../tags/tags.store';
import {
  createDocumentsApiSpy,
  createSearchApiSpy,
  createTagsApiSpy,
  makeDoc,
  makeTag,
  problem,
  ref,
} from '../tags/tags.spec-helpers';

describe('DocumentsStore (tags)', () => {
  let docsApi: jasmine.SpyObj<DocumentsService>;
  let searchApi: jasmine.SpyObj<SearchService>;
  let tagsApi: jasmine.SpyObj<TagsService>;
  let store: DocumentsStore;
  let tags: TagsStore;

  const tax = makeTag('tax');
  const paid = makeTag('paid', { color: '#57ab5a' });

  beforeEach(() => {
    docsApi = createDocumentsApiSpy();
    searchApi = createSearchApiSpy();
    tagsApi = createTagsApiSpy();
    TestBed.configureTestingModule({
      providers: [
        { provide: DocumentsService, useValue: docsApi },
        { provide: SearchService, useValue: searchApi },
        { provide: TagsService, useValue: tagsApi },
        { provide: FoldersService, useValue: jasmine.createSpyObj('FoldersService', ['listFolders']) },
      ],
    });
    (docsApi.listDocuments as jasmine.Spy).and.returnValue(of({ items: [], page: 0, pageSize: 100, totalCount: 0 }));
    (searchApi.searchDocuments as jasmine.Spy).and.returnValue(of({ items: [], totalCount: 0, page: 0, pageSize: 50 }));
    (tagsApi.listTags as jasmine.Spy).and.returnValue(of({ tags: [tax, paid] }));
    store = TestBed.inject(DocumentsStore);
    tags = TestBed.inject(TagsStore);
  });

  async function seedRows(...rows: ReturnType<typeof makeDoc>[]): Promise<void> {
    (docsApi.listDocuments as jasmine.Spy).and.returnValue(
      of({ items: rows, page: 0, pageSize: 100, totalCount: rows.length }),
    );
    await store.loadDocuments();
    docsApi.listDocuments.calls.reset();
  }

  it('list without a tag filter sends no tagIds', async () => {
    await store.loadDocuments();

    expect(docsApi.listDocuments).toHaveBeenCalledWith(undefined, 0, 100, undefined);
  });

  it('list and search include folder and tag filter', async () => {
    store.selectFolder('folder-1');
    store.toggleTag(tax.id);
    store.toggleTag(paid.id);
    await store.loadDocuments();

    expect(docsApi.listDocuments).toHaveBeenCalledWith('folder-1', 0, 100, [tax.id, paid.id]);

    await store.runSearch('passport');

    expect(searchApi.searchDocuments).toHaveBeenCalledWith('passport', 'folder-1', 0, 50, [tax.id, paid.id]);
  });

  it('toggleTag adds/removes and reloads', () => {
    store.toggleTag(tax.id);
    expect(store.selectedTagIds()).toEqual([tax.id]);
    expect(docsApi.listDocuments).toHaveBeenCalledWith(undefined, 0, 100, [tax.id]);

    store.toggleTag(tax.id);
    expect(store.selectedTagIds()).toEqual([]);
    expect(docsApi.listDocuments).toHaveBeenCalledWith(undefined, 0, 100, undefined);
  });

  it('toggleTag re-runs an active search with the new filter', async () => {
    await store.runSearch('lease');
    searchApi.searchDocuments.calls.reset();

    store.toggleTag(paid.id);

    expect(searchApi.searchDocuments).toHaveBeenCalledWith('lease', undefined, 0, 50, [paid.id]);
  });

  it('clearTagFilter resets and reloads', () => {
    store.toggleTag(tax.id);
    docsApi.listDocuments.calls.reset();

    store.clearTagFilter();

    expect(store.selectedTagIds()).toEqual([]);
    expect(docsApi.listDocuments).toHaveBeenCalledWith(undefined, 0, 100, undefined);
  });

  it('addTag / removeTag patch the document row', async () => {
    await seedRows(makeDoc('d1'), makeDoc('d2'));
    (tagsApi.addDocumentTag as jasmine.Spy).and.returnValue(of({ documentId: 'd1', tags: [ref(tax)] }));

    const added = await store.addTag('d1', tax.id);

    expect(tagsApi.addDocumentTag).toHaveBeenCalledWith('d1', tax.id);
    expect(added).toEqual([ref(tax)]);
    expect(store.documents().find((d) => d.id === 'd1')?.tags).toEqual([ref(tax)]);
    expect(store.documents().find((d) => d.id === 'd2')?.tags).toEqual([]);
    // Counts in the sidebar are refreshed.
    expect(tagsApi.listTags).toHaveBeenCalled();

    (tagsApi.removeDocumentTag as jasmine.Spy).and.returnValue(of({ documentId: 'd1', tags: [] }));
    await store.removeTag('d1', tax.id);

    expect(tagsApi.removeDocumentTag).toHaveBeenCalledWith('d1', tax.id);
    expect(store.documents().find((d) => d.id === 'd1')?.tags).toEqual([]);
  });

  it('assignment reloads the list when a tag filter is active', async () => {
    store.toggleTag(tax.id);
    await seedRows(makeDoc('d1', [ref(tax)]));
    (tagsApi.removeDocumentTag as jasmine.Spy).and.returnValue(of({ documentId: 'd1', tags: [] }));

    await store.removeTag('d1', tax.id);

    // The row no longer matches the filter, so the list is re-queried.
    expect(docsApi.listDocuments).toHaveBeenCalledWith(undefined, 0, 100, [tax.id]);
  });

  it('tag assignment failure keeps row and sets error', async () => {
    await seedRows(makeDoc('d1', [ref(paid)]));
    (tagsApi.addDocumentTag as jasmine.Spy).and.returnValue(throwError(() => problem(404, 'tag-not-found')));

    expect(await store.addTag('d1', tax.id)).toBeNull();

    expect(store.documents()[0].tags).toEqual([ref(paid)]);
    expect(store.tagError()).toBe('That tag no longer exists.');

    store.clearTagError();
    expect(store.tagError()).toBeNull();
  });

  it('tag deletion prunes filter and strips chips', async () => {
    await tags.load();
    store.toggleTag(tax.id);
    store.toggleTag(paid.id);
    await seedRows(makeDoc('d1', [ref(paid), ref(tax)]));
    (tagsApi.deleteTag as jasmine.Spy).and.returnValue(of(undefined));

    expect(await store.deleteTag(tax.id)).toBeTrue();

    expect(store.selectedTagIds()).toEqual([paid.id]);
    expect(store.documents()[0].tags).toEqual([ref(paid)]);
    expect(tags.tags().map((t) => t.id)).toEqual([paid.id]);
    expect(docsApi.listDocuments).toHaveBeenCalledWith(undefined, 0, 100, [paid.id]);
  });

  it('failed tag deletion changes nothing', async () => {
    await tags.load();
    store.toggleTag(tax.id);
    await seedRows(makeDoc('d1', [ref(tax)]));
    (tagsApi.deleteTag as jasmine.Spy).and.returnValue(throwError(() => new Error('offline')));

    expect(await store.deleteTag(tax.id)).toBeFalse();

    expect(store.selectedTagIds()).toEqual([tax.id]);
    expect(store.documents()[0].tags).toEqual([ref(tax)]);
  });

  it('tag rename / recolor patches chips in place', async () => {
    await tags.load();
    await seedRows(makeDoc('d1', [ref(tax)]));
    const renamed = { ...tax, name: 'Taxes', color: '#e5534b' };
    (tagsApi.updateTag as jasmine.Spy).and.returnValue(of(renamed));

    await store.updateTag(tax.id, { name: 'Taxes', color: '#e5534b' });

    expect(store.documents()[0].tags).toEqual([{ id: tax.id, name: 'Taxes', color: '#e5534b' }]);
  });
});
