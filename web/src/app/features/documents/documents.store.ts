import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { DocumentsService, FoldersService, SearchService } from '../../core/api/generated';
import {
  CreateFolderRequest,
  DocumentDetail,
  DocumentSummary,
  FolderNode,
  SearchHit,
  UpdateFolderRequest,
} from '../../core/api/types';
import { presignedPut, sha256Hex, UploadProgress, UploadResult } from '../../core/upload/uploader';

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

  private readonly _folders = signal<FolderNode[]>([]);
  private readonly _documents = signal<DocumentSummary[]>([]);
  private readonly _searchHits = signal<SearchHit[]>([]);
  private readonly _selectedFolderId = signal<string | null>(null);
  private readonly _query = signal<string>('');
  private readonly _loading = signal<boolean>(false);
  private readonly _uploads = signal<UploadingFile[]>([]);
  private readonly _totalCount = signal<number>(0);

  readonly folders = this._folders.asReadonly();
  readonly documents = this._documents.asReadonly();
  readonly searchHits = this._searchHits.asReadonly();
  readonly selectedFolderId = this._selectedFolderId.asReadonly();
  readonly query = this._query.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly uploads = this._uploads.asReadonly();
  readonly totalCount = this._totalCount.asReadonly();
  readonly searching = computed(() => this._query().trim().length > 0);

  async loadFolders(): Promise<void> {
    const resp = await firstValueFrom(this.foldersApi.listFolders());
    this._folders.set(resp.folders ?? []);
  }

  async loadDocuments(): Promise<void> {
    this._loading.set(true);
    try {
      const folderId = this._selectedFolderId() ?? undefined;
      const resp = await firstValueFrom(this.documentsApi.listDocuments(folderId, 0, 100));
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
      this.searchApi.searchDocuments(q, this._selectedFolderId() ?? undefined, 0, 50),
    );
    this._searchHits.set(resp.items ?? []);
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
