import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';

import { TagsService } from '../../core/api/generated';
import { TagListResponse } from '../../core/api/types';
import { TagsStore, normalizeTagName } from './tags.store';
import { createTagsApiSpy, makeTag, problem } from './tags.spec-helpers';

describe('TagsStore', () => {
  let api: jasmine.SpyObj<TagsService>;
  let store: TagsStore;

  beforeEach(() => {
    api = createTagsApiSpy();
    TestBed.configureTestingModule({ providers: [{ provide: TagsService, useValue: api }] });
    store = TestBed.inject(TagsStore);
  });

  it('load sets tags and toggles loading', async () => {
    const pending = new Subject<TagListResponse>();
    (api.listTags as jasmine.Spy).and.returnValue(pending);

    const done = store.load();
    expect(store.loading()).toBeTrue();
    expect(store.loaded()).toBeFalse();

    pending.next({ tags: [makeTag('Tax', { documentCount: 3 })] });
    pending.complete();
    await done;

    expect(store.loading()).toBeFalse();
    expect(store.loaded()).toBeTrue();
    expect(store.tags().map((t) => t.name)).toEqual(['Tax']);
    expect(store.byId().get('id-Tax')?.documentCount).toBe(3);
  });

  it('load with no tags leaves an empty, loaded list', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [] }));

    await store.load();

    expect(store.tags()).toEqual([]);
    expect(store.loaded()).toBeTrue();
    expect(store.error()).toBeNull();
  });

  it('load failure exposes error', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [makeTag('keep')] }));
    await store.load();
    (api.listTags as jasmine.Spy).and.returnValue(throwError(() => problem(500, 'server')));

    await store.load();

    expect(store.error()).toBe('Could not load tags.');
    expect(store.tags().map((t) => t.name)).toEqual(['keep']);
    expect(store.loading()).toBeFalse();
  });

  it('create trims, sorts, rejects blank', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [makeTag('beta')] }));
    await store.load();
    (api.createTag as jasmine.Spy).and.returnValue(of(makeTag('Alpha', { color: '#57ab5a' })));

    const created = await store.create('  Alpha  ', '#57ab5a');

    expect(api.createTag).toHaveBeenCalledWith({ name: 'Alpha', color: '#57ab5a' });
    expect(created?.name).toBe('Alpha');
    expect(store.tags().map((t) => t.name)).toEqual(['Alpha', 'beta']);

    api.createTag.calls.reset();
    expect(await store.create('   ')).toBeNull();
    expect(api.createTag).not.toHaveBeenCalled();
    expect(store.error()).toBe("Tag name can't be empty.");
  });

  it('create rejects names longer than 64 characters client-side', async () => {
    expect(await store.create('x'.repeat(65))).toBeNull();
    expect(api.createTag).not.toHaveBeenCalled();
    expect(store.error()).toBe('Tag names can be at most 64 characters.');
  });

  it('create omits colour when none is chosen', async () => {
    (api.createTag as jasmine.Spy).and.returnValue(of(makeTag('plain')));

    await store.create('plain', null);

    expect(api.createTag).toHaveBeenCalledWith({ name: 'plain' });
  });

  it('create conflict maps to friendly error', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [makeTag('tax')] }));
    await store.load();
    (api.createTag as jasmine.Spy).and.returnValue(throwError(() => problem(409, 'tag-name-conflict')));

    expect(await store.create('TAX')).toBeNull();

    expect(store.error()).toBe('A tag with that name already exists.');
    expect(store.tags().length).toBe(1);
    expect(store.busy()).toBeFalse();
  });

  it('create surfaces the server detail for invalid-tag and the limit message', async () => {
    (api.createTag as jasmine.Spy).and.returnValue(
      throwError(() => problem(400, 'invalid-tag', 'Tag name must not contain control characters')),
    );
    await store.create('bad');
    expect(store.error()).toBe('Tag name must not contain control characters');

    (api.createTag as jasmine.Spy).and.returnValue(throwError(() => problem(409, 'tag-limit-reached')));
    await store.create('one more');
    expect(store.error()).toBe('You have reached the maximum number of tags.');
  });

  it('update replaces tag in list', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [makeTag('a'), makeTag('b')] }));
    await store.load();
    (api.updateTag as jasmine.Spy).and.returnValue(of(makeTag('z', { id: 'id-a', color: '#e5534b' })));

    const updated = await store.update('id-a', { name: 'z', color: '#e5534b' });

    expect(api.updateTag).toHaveBeenCalledWith('id-a', { name: 'z', color: '#e5534b' });
    expect(updated?.name).toBe('z');
    expect(store.tags().map((t) => t.name)).toEqual(['b', 'z']);
  });

  it('update of a vanished tag drops it and explains why', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [makeTag('a')] }));
    await store.load();
    (api.updateTag as jasmine.Spy).and.returnValue(throwError(() => problem(404, 'tag-not-found')));

    expect(await store.update('id-a', { name: 'b' })).toBeNull();

    expect(store.error()).toBe('That tag no longer exists.');
    expect(store.tags()).toEqual([]);
  });

  it('remove drops tag', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [makeTag('a'), makeTag('b')] }));
    await store.load();
    (api.deleteTag as jasmine.Spy).and.returnValue(of(undefined));

    expect(await store.remove('id-a')).toBeTrue();

    expect(api.deleteTag).toHaveBeenCalledWith('id-a');
    expect(store.tags().map((t) => t.name)).toEqual(['b']);
  });

  it('remove failure keeps the tag and sets error', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [makeTag('a')] }));
    await store.load();
    (api.deleteTag as jasmine.Spy).and.returnValue(throwError(() => new Error('offline')));

    expect(await store.remove('id-a')).toBeFalse();

    expect(store.tags().length).toBe(1);
    expect(store.error()).toBe('Something went wrong. Please try again.');
  });

  it('clearError resets the error', async () => {
    await store.create('');
    store.clearError();
    expect(store.error()).toBeNull();
  });

  it('normalizeTagName collapses whitespace and counts code points', () => {
    expect(normalizeTagName('  tax \t 2024 ')).toBe('tax 2024');
    expect(normalizeTagName('📄'.repeat(64))).toBe('📄'.repeat(64));
  });
});
