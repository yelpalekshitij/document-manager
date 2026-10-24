import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

import { TagsService } from '../../core/api/generated';
import {
  ApiError,
  CreateTagRequest,
  PROBLEM_INVALID_TAG,
  PROBLEM_TAG_LIMIT_REACHED,
  PROBLEM_TAG_NAME_CONFLICT,
  PROBLEM_TAG_NOT_FOUND,
  ProblemDetails,
  Tag,
  UpdateTagRequest,
} from '../../core/api/types';

/** Mirrors the backend rule: 64 characters, counted as code points. */
export const MAX_TAG_NAME_LENGTH = 64;

/** Preset palette for tag colours — keeps choices accessible via a plain select. */
export const TAG_COLORS: ReadonlyArray<{ label: string; value: string }> = [
  { label: 'Red', value: '#e5534b' },
  { label: 'Orange', value: '#d4843e' },
  { label: 'Yellow', value: '#c69026' },
  { label: 'Green', value: '#57ab5a' },
  { label: 'Teal', value: '#39c5cf' },
  { label: 'Blue', value: '#539bf5' },
  { label: 'Purple', value: '#b083f0' },
  { label: 'Pink', value: '#e275ad' },
];

/** Same normalisation the server applies (NFC, collapse whitespace, trim). */
export function normalizeTagName(raw: string): string {
  return raw.normalize('NFC').replace(/\s+/gu, ' ').trim();
}

export function byTagName(a: Pick<Tag, 'id' | 'name'>, b: Pick<Tag, 'id' | 'name'>): number {
  return a.name.toLowerCase().localeCompare(b.name.toLowerCase()) || a.id.localeCompare(b.id);
}

function problemOf(err: unknown): ProblemDetails | null {
  if (err instanceof ApiError) return err.problem;
  if (err instanceof HttpErrorResponse && err.error && typeof err.error === 'object') {
    return err.error as ProblemDetails;
  }
  return null;
}

/** Friendly copy for the tag problem types; falls back to a generic retry message. */
export function tagErrorMessage(err: unknown): string {
  const problem = problemOf(err);
  switch (problem?.type) {
    case PROBLEM_TAG_NAME_CONFLICT:
      return 'A tag with that name already exists.';
    case PROBLEM_TAG_LIMIT_REACHED:
      return 'You have reached the maximum number of tags.';
    case PROBLEM_TAG_NOT_FOUND:
      return 'That tag no longer exists.';
    case PROBLEM_INVALID_TAG:
      return problem.detail ?? 'That tag name or colour is not valid.';
    default:
      return 'Something went wrong. Please try again.';
  }
}

@Injectable({ providedIn: 'root' })
export class TagsStore {
  private readonly api = inject(TagsService);

  private readonly _tags = signal<Tag[]>([]);
  private readonly _loading = signal(false);
  private readonly _loaded = signal(false);
  private readonly _busy = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly tags = this._tags.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly loaded = this._loaded.asReadonly();
  /** True while a create/update/delete is in flight — forms disable their submit. */
  readonly busy = this._busy.asReadonly();
  readonly error = this._error.asReadonly();
  readonly byId = computed(() => new Map(this._tags().map((t) => [t.id, t])));

  async load(): Promise<void> {
    this._loading.set(true);
    try {
      const resp = await firstValueFrom(this.api.listTags());
      this._tags.set([...(resp.tags ?? [])].sort(byTagName));
      this._loaded.set(true);
      this._error.set(null);
    } catch {
      this._error.set('Could not load tags.');
    } finally {
      this._loading.set(false);
    }
  }

  async create(rawName: string, color?: string | null): Promise<Tag | null> {
    const name = this.validName(rawName);
    if (name === null) return null;
    const req: CreateTagRequest = color ? { name, color } : { name };
    return this.run(async () => {
      const created = await firstValueFrom(this.api.createTag(req));
      this._tags.update((list) => [...list, created].sort(byTagName));
      return created;
    });
  }

  async update(id: string, patch: UpdateTagRequest): Promise<Tag | null> {
    const req: UpdateTagRequest = { ...patch };
    if (patch.name !== undefined) {
      const name = this.validName(patch.name);
      if (name === null) return null;
      req.name = name;
    }
    return this.run(
      async () => {
        const updated = await firstValueFrom(this.api.updateTag(id, req));
        this._tags.update((list) => list.map((t) => (t.id === id ? updated : t)).sort(byTagName));
        return updated;
      },
      this.dropIfGone(id),
    );
  }

  async remove(id: string): Promise<boolean> {
    const ok = await this.run(
      async () => {
        await firstValueFrom(this.api.deleteTag(id));
        this._tags.update((list) => list.filter((t) => t.id !== id));
        return true;
      },
      this.dropIfGone(id),
    );
    return ok === true;
  }

  clearError(): void {
    this._error.set(null);
  }

  private validName(raw: string): string | null {
    const name = normalizeTagName(raw);
    if (!name) {
      this._error.set("Tag name can't be empty.");
      return null;
    }
    if ([...name].length > MAX_TAG_NAME_LENGTH) {
      this._error.set(`Tag names can be at most ${MAX_TAG_NAME_LENGTH} characters.`);
      return null;
    }
    return name;
  }

  private async run<T>(op: () => Promise<T>, onError?: (err: unknown) => void): Promise<T | null> {
    this._busy.set(true);
    this._error.set(null);
    try {
      return await op();
    } catch (err) {
      this._error.set(tagErrorMessage(err));
      onError?.(err);
      return null;
    } finally {
      this._busy.set(false);
    }
  }

  private dropIfGone(id: string): (err: unknown) => void {
    return (err) => {
      if (problemOf(err)?.type === PROBLEM_TAG_NOT_FOUND) {
        this._tags.update((list) => list.filter((t) => t.id !== id));
      }
    };
  }
}
