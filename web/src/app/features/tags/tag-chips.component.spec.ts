import { ComponentFixture, TestBed } from '@angular/core/testing';
import { WritableSignal, signal } from '@angular/core';
import { of } from 'rxjs';

import { TagsService } from '../../core/api/generated';
import { TagRef } from '../../core/api/types';
import { DocumentsStore } from '../documents/documents.store';
import { TagChipsComponent } from './tag-chips.component';
import { TagsStore } from './tags.store';
import { createTagsApiSpy, makeTag, ref } from './tags.spec-helpers';

describe('TagChipsComponent', () => {
  const tax = makeTag('tax', { color: '#e5534b' });
  const paid = makeTag('paid');
  const urgent = makeTag('urgent');

  let docs: { addTag: jasmine.Spy; removeTag: jasmine.Spy; tagError: WritableSignal<string | null> };
  let fixture: ComponentFixture<TagChipsComponent>;
  let el: HTMLElement;

  async function render(tags: TagRef[], editable: boolean): Promise<void> {
    fixture = TestBed.createComponent(TagChipsComponent);
    fixture.componentRef.setInput('documentId', 'd1');
    fixture.componentRef.setInput('tags', tags);
    fixture.componentRef.setInput('editable', editable);
    fixture.detectChanges();
    await fixture.whenStable();
    el = fixture.nativeElement as HTMLElement;
  }

  beforeEach(async () => {
    const api = createTagsApiSpy();
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [paid, tax, urgent] }));
    docs = {
      addTag: jasmine.createSpy('addTag'),
      removeTag: jasmine.createSpy('removeTag'),
      tagError: signal<string | null>(null),
    };
    TestBed.configureTestingModule({
      imports: [TagChipsComponent],
      providers: [
        { provide: TagsService, useValue: api },
        { provide: DocumentsStore, useValue: docs },
      ],
    });
    await TestBed.inject(TagsStore).load();
  });

  it('renders chips with accessible remove buttons', async () => {
    await render([ref(tax), ref(paid)], true);

    const chips = Array.from(el.querySelectorAll('li.chip')).map((li) => li.querySelector('.label')?.textContent?.trim());
    expect(chips).toEqual(['tax', 'paid']);
    const list = el.querySelector('ul');
    expect(list?.getAttribute('aria-label')).toBe('Tags');

    const remove = el.querySelector<HTMLButtonElement>('button[aria-label="Remove tag tax"]');
    expect(remove).not.toBeNull();
    expect(remove?.type).toBe('button');

    docs.removeTag.and.resolveTo([ref(paid)]);
    let emitted: TagRef[] | undefined;
    fixture.componentInstance.changed.subscribe((t) => (emitted = t));
    remove!.click();
    await fixture.whenStable();

    expect(docs.removeTag).toHaveBeenCalledWith('d1', tax.id);
    expect(emitted).toEqual([ref(paid)]);
  });

  it('add select offers only unattached tags', async () => {
    await render([ref(tax)], true);

    const select = el.querySelector<HTMLSelectElement>('select')!;
    expect(select.getAttribute('aria-label')).toBe('Add tag');
    const options = Array.from(select.options).filter((o) => o.value).map((o) => o.textContent?.trim());
    expect(options).toEqual(['paid', 'urgent']);

    docs.addTag.and.resolveTo([ref(paid), ref(tax)]);
    select.value = paid.id;
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(docs.addTag).toHaveBeenCalledWith('d1', paid.id);
    expect(select.value).toBe('');
  });

  it('add select is disabled when every tag is attached', async () => {
    await render([ref(tax), ref(paid), ref(urgent)], true);

    expect(el.querySelector<HTMLSelectElement>('select')!.disabled).toBeTrue();
  });

  it('shows a failed assignment as an alert scoped to this document', async () => {
    await render([ref(tax)], true);
    docs.removeTag.and.callFake(async () => {
      docs.tagError.set('That tag no longer exists.');
      return null;
    });

    el.querySelector<HTMLButtonElement>('button[aria-label="Remove tag tax"]')!.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el.querySelector('[role="alert"]')?.textContent).toContain('That tag no longer exists.');
  });

  it('readonly hides controls', async () => {
    await render([ref(tax)], false);

    expect(el.querySelector('li.chip .label')?.textContent?.trim()).toBe('tax');
    expect(el.querySelector('button')).toBeNull();
    expect(el.querySelector('select')).toBeNull();
  });

  it('readonly with no tags renders nothing', async () => {
    await render([], false);

    expect(el.querySelector('ul')).toBeNull();
  });
});
