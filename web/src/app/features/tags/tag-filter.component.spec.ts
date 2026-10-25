import { ComponentFixture, TestBed } from '@angular/core/testing';
import { WritableSignal, signal } from '@angular/core';
import { Subject, of, throwError } from 'rxjs';

import { TagsService } from '../../core/api/generated';
import { TagListResponse } from '../../core/api/types';
import { DocumentsStore } from '../documents/documents.store';
import { TagFilterComponent } from './tag-filter.component';
import { TagsStore } from './tags.store';
import { createTagsApiSpy, makeTag, problem } from './tags.spec-helpers';

describe('TagFilterComponent', () => {
  const tax = makeTag('tax', { documentCount: 3, color: '#e5534b' });
  const paid = makeTag('paid', { documentCount: 1 });

  let api: jasmine.SpyObj<TagsService>;
  let docs: { selectedTagIds: WritableSignal<string[]>; toggleTag: jasmine.Spy; clearTagFilter: jasmine.Spy };
  let fixture: ComponentFixture<TagFilterComponent>;
  let el: HTMLElement;

  beforeEach(() => {
    api = createTagsApiSpy();
    docs = {
      selectedTagIds: signal<string[]>([]),
      toggleTag: jasmine.createSpy('toggleTag'),
      clearTagFilter: jasmine.createSpy('clearTagFilter'),
    };
    TestBed.configureTestingModule({
      imports: [TagFilterComponent],
      providers: [
        { provide: TagsService, useValue: api },
        { provide: DocumentsStore, useValue: docs },
      ],
    });
  });

  async function render(): Promise<void> {
    fixture = TestBed.createComponent(TagFilterComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  function toggles(): HTMLButtonElement[] {
    return Array.from(el.querySelectorAll<HTMLButtonElement>('button.toggle'));
  }

  it('renders tags with counts and aria-pressed', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [paid, tax] }));
    docs.selectedTagIds.set([tax.id]);
    await render();

    const section = el.querySelector('section')!;
    const heading = el.querySelector(`#${section.getAttribute('aria-labelledby')}`);
    expect(heading?.textContent?.trim()).toBe('Tags');

    const [paidBtn, taxBtn] = toggles();
    expect(paidBtn.textContent).toContain('paid');
    expect(paidBtn.querySelector('.count')?.textContent?.trim()).toBe('1');
    expect(paidBtn.getAttribute('aria-pressed')).toBe('false');
    expect(taxBtn.getAttribute('aria-pressed')).toBe('true');
    // Native <button>: Enter/Space activate it, so a click covers keyboard use.
    expect(taxBtn.type).toBe('button');

    paidBtn.click();
    expect(docs.toggleTag).toHaveBeenCalledWith(paid.id);
  });

  it('shows loading state before the first load completes', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(new Subject<TagListResponse>());
    fixture = TestBed.createComponent(TagFilterComponent);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('Loading tags');
    expect(toggles().length).toBe(0);
  });

  it('shows empty state', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [] }));
    await render();

    expect(el.querySelector('.empty')?.textContent).toContain('No tags yet');
    expect(toggles().length).toBe(0);
  });

  it('shows an error with a retry when tags fail to load', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(throwError(() => problem(500, 'server')));
    await render();

    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Could not load tags.');

    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [tax] }));
    el.querySelector<HTMLButtonElement>('button.retry')!.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(toggles().length).toBe(1);
  });

  it('clear button appears only with active filter', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [paid, tax] }));
    await render();
    expect(el.querySelector('button.clear')).toBeNull();

    docs.selectedTagIds.set([tax.id, paid.id]);
    fixture.detectChanges();

    const clear = el.querySelector<HTMLButtonElement>('button.clear')!;
    expect(clear.getAttribute('aria-label')).toBe('Clear tag filter');
    expect(el.querySelector('.hint')?.textContent).toContain('all selected tags');
    clear.click();
    expect(docs.clearTagFilter).toHaveBeenCalled();
  });

  it('unselected toggles are disabled once 20 tags are selected', async () => {
    const many = Array.from({ length: 21 }, (_, i) => makeTag(`tag-${String(i).padStart(2, '0')}`));
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: many }));
    docs.selectedTagIds.set(many.slice(0, 20).map((t) => t.id));
    await render();

    const buttons = toggles();
    const selected = buttons.filter((b) => b.getAttribute('aria-pressed') === 'true');
    const unselected = buttons.filter((b) => b.getAttribute('aria-pressed') === 'false');
    expect(selected.length).toBe(20);
    expect(selected.every((b) => !b.disabled)).toBeTrue();   // can still deselect
    expect(unselected.length).toBe(1);
    expect(unselected[0].disabled).toBeTrue();
    expect(el.querySelector('.limit')?.textContent).toContain('20');

    docs.selectedTagIds.set(many.slice(0, 19).map((t) => t.id));
    fixture.detectChanges();
    expect(toggles().every((b) => !b.disabled)).toBeTrue();
    expect(el.querySelector('.limit')).toBeNull();
  });

  it('manage button emits manage', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [] }));
    await render();
    let opened = false;
    fixture.componentInstance.manage.subscribe(() => (opened = true));

    el.querySelector<HTMLButtonElement>('button.manage')!.click();

    expect(opened).toBeTrue();
  });

  it('does not reload tags that are already loaded', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [tax] }));
    await TestBed.inject(TagsStore).load();
    api.listTags.calls.reset();

    await render();

    expect(api.listTags).not.toHaveBeenCalled();
    expect(toggles().length).toBe(1);
  });
});
