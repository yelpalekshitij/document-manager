import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { DocumentsService, FoldersService, SearchService, TagsService } from '../../core/api/generated';
import {
  CreateFolderRequest,
  DocumentDetail,
  DocumentSummary,
  DocumentTagsResponse,
  FolderNode,
  SearchHit,
  Tag,
  TagRef,
  UpdateFolderRequest,
  UpdateTagRequest,
} from '../../core/api/types';
import { presignedPut, sha256Hex, UploadProgress, UploadResult } from '../../core/upload/uploader';
import { TagsStore, tagErrorMessage } from '../tags/tags.store';

export interface UploadingFile {
  id: string;
  filename: string;
  sizeBytes: number;
  percent: number;
  status: 'preparing' | 'uploading' | 'finalizing' | 'done' | 'error';
  error?: string;
  documentId?: string;
}

@Injectable({ providedIn: 'root' })
export class DocumentsStore {
  private readonly documentsApi = inject(DocumentsService);
  private readonly foldersApi = inject(FoldersService);
  private readonly searchApi = inject(SearchService);
  private readonly tagsApi = inject(TagsService);
  private readonly tagsStore = inject(TagsStore);

  private readonly _folders = signal<FolderNode[]>([]);
  private readonly _documents = signal<DocumentSummary[]>([]);
  private readonly _searchHits = signal<SearchHit[]>([]);
  private readonly _selectedFolderId = signal<string | null>(null);
  private readonly _query = signal<string>('');
  private readonly _loading = signal<boolean>(false);
  private readonly _uploads = signal<UploadingFile[]>([]);
  private readonly _totalCount = signal<number>(0);
  /** Tag filter (AND semantics), combined with the folder and the search query. */
  private readonly _selectedTagIds = signal<string[]>([]);
  private readonly _tagError = signal<string | null>(null);

  readonly folders = this._folders.asReadonly();
  readonly documents = this._documents.asReadonly();
  readonly searchHits = this._searchHits.asReadonly();
  readonly selectedFolderId = this._selectedFolderId.asReadonly();
  readonly query = this._query.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly uploads = this._uploads.asReadonly();
  readonly totalCount = this._totalCount.asReadonly();
  readonly searching = computed(() => this._query().trim().length > 0);
  readonly selectedTagIds = this._selectedTagIds.asReadonly();
  /** Last tag-assignment / tag-edit failure, shown next to the chips. */
  readonly tagError = this._tagError.asReadonly();

  async loadFolders(): Promise<void> {
    const resp = await firstValueFrom(this.foldersApi.listFolders());
    this._folders.set(resp.folders ?? []);
  }

  async loadDocuments(): Promise<void> {
    this._loading.set(true);
    try {
      const folderId = this._selectedFolderId() ?? undefined;
      const resp = await firstValueFrom(
        this.documentsApi.listDocuments(folderId, 0, 100, this.tagFilter()),
      );
      this._documents.set(resp.items ?? []);
      this._totalCount.set(resp.totalCount ?? 0);
    } finally {
      this._loading.set(false);
    }
  }

  async runSearch(q: string): Promise<void> {
    this._query.set(q);
    if (!q.trim()) {
      this._searchHits.set([]);
      return;
    }
    const resp = await firstValueFrom(
      this.searchApi.searchDocuments(q, this._selectedFolderId() ?? undefined, 0, 50, this.tagFilter()),
    );
    this._searchHits.set(resp.items ?? []);
  }

  // ── Tag filter ───────────────────────────────────────────────────────────

  toggleTag(tagId: string): void {
    this._selectedTagIds.update((ids) =>
      ids.includes(tagId) ? ids.filter((id) => id !== tagId) : [...ids, tagId],
    );
    this.refreshResults();
  }

  clearTagFilter(): void {
    if (this._selectedTagIds().length === 0) return;
    this._selectedTagIds.set([]);
    this.refreshResults();
  }

  // ── Tag assignment ───────────────────────────────────────────────────────

  /** Attach a tag; returns the document's resulting tags, or null on failure. */
  addTag(documentId: string, tagId: string): Promise<TagRef[] | null> {
    return this.assign(documentId, this.tagsApi.addDocumentTag(documentId, tagId));
  }

  /** Detach a tag; returns the document's resulting tags, or null on failure. */
  removeTag(documentId: string, tagId: string): Promise<TagRef[] | null> {
    return this.assign(documentId, this.tagsApi.removeDocumentTag(documentId, tagId));
  }

  clearTagError(): void {
    this._tagError.set(null);
  }

  // ── Tag edits that ripple into the document rows ─────────────────────────

  async updateTag(id: string, patch: UpdateTagRequest): Promise<Tag | null> {
    const updated = await this.tagsStore.update(id, patch);
    if (updated) {
      const chip: TagRef = { id: updated.id, name: updated.name, color: updated.color };
      this.patchTags((tags) => tags.map((t) => (t.id === id ? chip : t)));
    }
    return updated;
  }

  async deleteTag(id: string): Promise<boolean> {
    const ok = await this.tagsStore.remove(id);
    if (!ok) return false;
    this.patchTags((tags) => tags.filter((t) => t.id !== id));
    if (this._selectedTagIds().includes(id)) {
      this._selectedTagIds.update((ids) => ids.filter((x) => x !== id));
      await this.reloadResults();
    }
    return true;
  }

  private async assign(documentId: string, call: Observable<DocumentTagsResponse>): Promise<TagRef[] | null> {
    this._tagError.set(null);
    try {
      const resp = await firstValueFrom(call);
      const tags = resp.tags ?? [];
      this._documents.update((rows) => rows.map((d) => (d.id === documentId ? { ...d, tags } : d)));
      void this.tagsStore.load();   // document counts changed
      if (this._selectedTagIds().length > 0) {
        await this.reloadResults();   // the row may no longer match the filter
      }
      return tags;
    } catch (err) {
      this._tagError.set(tagErrorMessage(err));
      return null;
    }
  }

  private patchTags(fn: (tags: TagRef[]) => TagRef[]): void {
    this._documents.update((rows) => rows.map((d) => ({ ...d, tags: fn(d.tags ?? []) })));
  }

  /** `undefined` (no query param at all) rather than `[]` when nothing is selected. */
  private tagFilter(): string[] | undefined {
    const ids = this._selectedTagIds();
    return ids.length > 0 ? [...ids] : undefined;
  }

  private refreshResults(): void {
    void this.reloadResults();
  }

  private async reloadResults(): Promise<void> {
    const pending: Promise<void>[] = [this.loadDocuments()];
    if (this.searching()) pending.push(this.runSearch(this._query()));
    await Promise.all(pending);
  }

  selectFolder(folderId: string | null): void {
    this._selectedFolderId.set(folderId);
    void this.loadDocuments();
  }

  async createFolder(req: CreateFolderRequest): Promise<FolderNode> {
    const created = await firstValueFrom(this.foldersApi.createFolder(req));
    await this.loadFolders();
    return created;
  }

  async updateFolder(id: string, req: UpdateFolderRequest): Promise<FolderNode> {
    const updated = await firstValueFrom(this.foldersApi.updateFolder(id, req));
    await this.loadFolders();
    return updated;
  }

  async deleteFolder(id: string): Promise<void> {
    await firstValueFrom(this.foldersApi.deleteFolder(id));
    if (this._selectedFolderId() === id) this._selectedFolderId.set(null);
    await Promise.all([this.loadFolders(), this.loadDocuments()]);
  }

  async deleteDocument(id: string): Promise<void> {
    await firstValueFrom(this.documentsApi.deleteDocument(id));
    if (this.searching()) {
      await this.runSearch(this._query());
    }
    await this.loadDocuments();
  }

  async getDetail(id: string): Promise<DocumentDetail> {
    return firstValueFrom(this.documentsApi.getDocument(id));
  }

  async getDownloadUrl(id: string): Promise<string> {
    const resp = await firstValueFrom(this.documentsApi.getDocumentDownload(id));
    return resp.downloadUrl;
  }

  async uploadFile(file: File): Promise<void> {
    const tempId = crypto.randomUUID();
    const tracker: UploadingFile = {
      id: tempId,
      filename: file.name,
      sizeBytes: file.size,
      percent: 0,
      status: 'preparing',
    };
    this._uploads.update((list) => [...list, tracker]);

    try {
      const buffer = await file.arrayBuffer();
      const sha = await sha256Hex(buffer);
      const folderId = this._selectedFolderId() ?? undefined;

      const initiate = await firstValueFrom(
        this.documentsApi.initiateUpload({
          filename: file.name,
          mimeType: file.type || 'application/octet-stream',
          sizeBytes: file.size,
          sha256: sha,
          folderId,
        }),
      );

      this.patchUpload(tempId, { status: 'uploading', documentId: initiate.documentId });

      const blob = new Blob([buffer], { type: file.type });
      await this.putWithProgress(initiate.uploadUrl, blob, file.type || 'application/octet-stream', tempId);

      this.patchUpload(tempId, { status: 'finalizing', percent: 100 });

      await firstValueFrom(
        this.documentsApi.finalizeUpload({ documentId: initiate.documentId }),
      );

      this.patchUpload(tempId, { status: 'done' });
      await this.loadDocuments();
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      this.patchUpload(tempId, { status: 'error', error: message });
    }
  }

  dismissUpload(id: string): void {
    this._uploads.update((list) => list.filter((u) => u.id !== id));
  }

  private patchUpload(id: string, patch: Partial<UploadingFile>): void {
    this._uploads.update((list) =>
      list.map((u) => (u.id === id ? { ...u, ...patch } : u)),
    );
  }

  private putWithProgress(url: string, blob: Blob, contentType: string, trackerId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      presignedPut(url, blob, contentType).subscribe({
        next: (event: UploadProgress | UploadResult) => {
          if ('percent' in event) {
            this.patchUpload(trackerId, { percent: event.percent });
          }
        },
        error: (err) => reject(err),
        complete: () => resolve(),
      });
    });
  }
}
