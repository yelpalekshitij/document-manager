import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { TagsService } from '../../core/api/generated';
import { DocumentsStore } from '../documents/documents.store';
import { TagManagerComponent } from './tag-manager.component';
import { TagsStore } from './tags.store';
import { createTagsApiSpy, makeTag, problem } from './tags.spec-helpers';

describe('TagManagerComponent', () => {
  const tax = makeTag('tax', { documentCount: 4, color: '#e5534b' });
  const paid = makeTag('paid');

  let api: jasmine.SpyObj<TagsService>;
  let docs: { updateTag: jasmine.Spy; deleteTag: jasmine.Spy };
  let fixture: ComponentFixture<TagManagerComponent>;
  let el: HTMLElement;

  beforeEach(async () => {
    api = createTagsApiSpy();
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [paid, tax] }));
    docs = {
      updateTag: jasmine.createSpy('updateTag').and.resolveTo(tax),
      deleteTag: jasmine.createSpy('deleteTag').and.resolveTo(true),
    };
    TestBed.configureTestingModule({
      imports: [TagManagerComponent],
      providers: [
        { provide: TagsService, useValue: api },
        { provide: DocumentsStore, useValue: docs },
      ],
    });
    await TestBed.inject(TagsStore).load();
    fixture = TestBed.createComponent(TagManagerComponent);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  });

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function type(input: HTMLInputElement, value: string): void {
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function row(name: string): HTMLElement {
    return Array.from(el.querySelectorAll<HTMLElement>('li.row')).find((li) =>
      li.querySelector('.name')?.textContent?.includes(name),
    )!;
  }

  it('is a labelled modal dialog that closes on Escape and via the close button', () => {
    const dialog = el.querySelector('[role="dialog"]')!;
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(el.querySelector(`#${dialog.getAttribute('aria-labelledby')}`)?.textContent).toContain('Manage tags');

    let closed = 0;
    fixture.componentInstance.close.subscribe(() => closed++);
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    el.querySelector<HTMLButtonElement>('button[aria-label="Close"]')!.click();

    expect(closed).toBe(2);
  });

  it('create form is labelled and disabled when blank', async () => {
    const name = el.querySelector<HTMLInputElement>('#new-tag-name')!;
    expect(el.querySelector('label[for="new-tag-name"]')?.textContent).toContain('Name');
    expect(el.querySelector('label[for="new-tag-color"]')?.textContent).toContain('Colour');
    const submit = el.querySelector<HTMLButtonElement>('form.create button[type="submit"]')!;
    expect(submit.disabled).toBeTrue();

    type(name, '   ');
    expect(submit.disabled).toBeTrue();

    type(name, 'Medical');
    const color = el.querySelector<HTMLSelectElement>('#new-tag-color')!;
    color.value = '#57ab5a';
    color.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(submit.disabled).toBeFalse();

    (api.createTag as jasmine.Spy).and.returnValue(of(makeTag('Medical', { color: '#57ab5a' })));
    submit.click();
    await settle();

    expect(api.createTag).toHaveBeenCalledWith({ name: 'Medical', color: '#57ab5a' });
    expect(name.value).toBe('');
    expect(row('Medical')).toBeTruthy();
  });

  it('keeps the typed name when create fails', async () => {
    (api.createTag as jasmine.Spy).and.returnValue(throwError(() => problem(409, 'tag-name-conflict')));
    const name = el.querySelector<HTMLInputElement>('#new-tag-name')!;
    type(name, 'TAX');

    el.querySelector<HTMLButtonElement>('form.create button[type="submit"]')!.click();
    await settle();

    expect(name.value).toBe('TAX');
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('A tag with that name already exists.');
  });

  it('rename inline and escape cancels', async () => {
    row('tax').querySelector<HTMLButtonElement>('button.rename')!.click();
    fixture.detectChanges();
    // The row now shows an input pre-filled with the current name.
    let input = el.querySelector<HTMLInputElement>('input.rename-input');
    expect(input?.value).toBe('tax');
    expect(input?.getAttribute('aria-label')).toBe('New name for tax');

    input!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(el.querySelector('input.rename-input')).toBeNull();
    expect(docs.updateTag).not.toHaveBeenCalled();

    row('tax').querySelector<HTMLButtonElement>('button.rename')!.click();
    fixture.detectChanges();
    input = el.querySelector<HTMLInputElement>('input.rename-input')!;
    type(input, 'Taxes');
    input.closest('form')!.dispatchEvent(new Event('submit'));
    await settle();

    expect(docs.updateTag).toHaveBeenCalledWith(tax.id, { name: 'Taxes' });
    expect(el.querySelector('input.rename-input')).toBeNull();
  });

  it('Escape inside the rename field does not close the dialog', () => {
    let closed = false;
    fixture.componentInstance.close.subscribe(() => (closed = true));
    row('tax').querySelector<HTMLButtonElement>('button.rename')!.click();
    fixture.detectChanges();

    el.querySelector<HTMLInputElement>('input.rename-input')!
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

    expect(closed).toBeFalse();
  });

  it('recolors via the labelled colour select; "None" clears', async () => {
    const select = row('tax').querySelector<HTMLSelectElement>('select.recolor')!;
    expect(select.getAttribute('aria-label')).toBe('Colour for tax');
    expect(select.value).toBe('#e5534b');

    select.value = '#539bf5';
    select.dispatchEvent(new Event('change'));
    await settle();
    expect(docs.updateTag).toHaveBeenCalledWith(tax.id, { color: '#539bf5' });

    select.value = '';
    select.dispatchEvent(new Event('change'));
    await settle();
    expect(docs.updateTag).toHaveBeenCalledWith(tax.id, { color: '' });
  });

  it('delete requires confirmation', async () => {
    row('tax').querySelector<HTMLButtonElement>('button.delete')!.click();
    fixture.detectChanges();

    const confirmBox = el.querySelector<HTMLElement>('[role="alertdialog"]')!;
    expect(confirmBox.textContent).toContain('Delete “tax”?');
    expect(confirmBox.textContent).toContain('removed from 4 documents');
    expect(docs.deleteTag).not.toHaveBeenCalled();

    confirmBox.querySelector<HTMLButtonElement>('button.cancel')!.click();
    fixture.detectChanges();
    expect(el.querySelector('[role="alertdialog"]')).toBeNull();
    expect(docs.deleteTag).not.toHaveBeenCalled();

    row('tax').querySelector<HTMLButtonElement>('button.delete')!.click();
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('[role="alertdialog"] button.confirm')!.click();
    await settle();

    expect(docs.deleteTag).toHaveBeenCalledWith(tax.id);
    expect(el.querySelector('[role="alertdialog"]')).toBeNull();
  });

  it('shows store error as alert', async () => {
    (api.createTag as jasmine.Spy).and.returnValue(throwError(() => problem(409, 'tag-limit-reached')));
    type(el.querySelector<HTMLInputElement>('#new-tag-name')!, 'more');

    el.querySelector<HTMLButtonElement>('form.create button[type="submit"]')!.click();
    await settle();

    expect(el.querySelector('[role="alert"]')?.textContent).toContain('maximum number of tags');
  });

  it('shows an empty state when there are no tags', async () => {
    (api.listTags as jasmine.Spy).and.returnValue(of({ tags: [] }));
    await TestBed.inject(TagsStore).load();
    fixture.detectChanges();

    expect(el.querySelector('.empty')?.textContent).toContain('No tags yet');
    expect(el.querySelectorAll('li.row').length).toBe(0);
  });
});
