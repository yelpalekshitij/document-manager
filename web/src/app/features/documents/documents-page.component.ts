import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';

import { AuthStore } from '../../core/auth/auth.store';
import { DocumentsStore } from './documents.store';
import { DocumentListComponent } from './document-list.component';
import { DocumentViewerComponent } from './document-viewer.component';
import { FolderTreeComponent } from './folder-tree.component';
import { SearchBarComponent } from './search-bar.component';
import { UploadZoneComponent } from './upload-zone.component';
import { DocumentSummary, SearchHit } from '../../core/api/types';

@Component({
  selector: 'vd-documents-page',
  standalone: true,
  imports: [
    DatePipe,
    RouterLink,
    DocumentListComponent,
    DocumentViewerComponent,
    FolderTreeComponent,
    SearchBarComponent,
    UploadZoneComponent,
  ],
  template: `
    <div class="shell">
      <aside>
        <header class="brand">
          <h1>VaultDoc</h1>
          @if (auth.user(); as u) {
            <p class="who">{{ u.email }}</p>
          }
        </header>
        <vd-folder-tree />
        <div class="spacer"></div>
        <nav class="links">
          <a routerLink="/">Dashboard</a>
          <button type="button" class="link" (click)="signOut()">Sign out</button>
        </nav>
      </aside>

      <main>
        <div class="topbar">
          <h2>{{ heading() }}</h2>
          <vd-search-bar />
        </div>

        <vd-upload-zone />

        @if (store.searching()) {
          <section class="results">
            <h3>Search results</h3>
            @if (store.searchHits().length === 0) {
              <p class="muted">No matches for "{{ store.query() }}".</p>
            } @else {
              <ul class="hits">
                @for (h of store.searchHits(); track h.documentId) {
                  <li (click)="openFromHit(h)">
                    <span class="filename">{{ h.filename }}</span>
                    <span class="uploaded">{{ h.uploadedAt | date: 'mediumDate' }}</span>
                  </li>
                }
              </ul>
            }
          </section>
        }

        <section class="docs">
          <vd-document-list (opened)="open($event)" />
        </section>
      </main>
    </div>

    @if (selected(); as doc) {
      <vd-document-viewer [doc]="doc" (close)="selected.set(null)" />
    }
  `,
  styles: [
    `
      :host { display: block; min-height: 100vh; background: var(--vd-bg); color: var(--vd-fg); }
      .shell {
        display: grid;
        grid-template-columns: 260px 1fr;
        min-height: 100vh;
      }
      aside {
        background: var(--vd-bg-card);
        border-right: 1px solid var(--vd-border);
        padding: 1.25rem 0.75rem;
        display: flex; flex-direction: column;
      }
      .brand { padding: 0 0.5rem 1rem; }
      .brand h1 { margin: 0; font-size: 1.1rem; }
      .who { margin: 0.2rem 0 0; color: var(--vd-fg-muted); font-size: 0.8rem; overflow: hidden; text-overflow: ellipsis; }
      .spacer { flex: 1; }
      .links { display: flex; flex-direction: column; gap: 0.4rem; padding: 0 0.5rem; }
      .links a, .link {
        background: transparent; border: none;
        color: var(--vd-fg-muted); text-decoration: none;
        font: inherit; cursor: pointer; text-align: left; padding: 0.3rem 0;
      }
      .links a:hover, .link:hover { color: var(--vd-accent); }

      main { padding: 1.5rem 2rem; display: flex; flex-direction: column; gap: 1.25rem; max-width: 1100px; }
      .topbar {
        display: grid;
        grid-template-columns: 1fr 320px;
        gap: 1rem; align-items: center;
      }
      h2 { margin: 0; font-size: 1.4rem; }

      .results, .docs {
        background: var(--vd-bg-card);
        border: 1px solid var(--vd-border);
        border-radius: 12px;
        padding: 1rem 1.25rem;
      }
      .results h3 { margin: 0 0 0.5rem; font-size: 0.9rem; color: var(--vd-fg-muted); text-transform: uppercase; letter-spacing: 0.04em; }
      .hits { list-style: none; padding: 0; margin: 0; }
      .hits li {
        display: flex; justify-content: space-between;
        padding: 0.45rem 0.4rem; border-bottom: 1px solid var(--vd-border);
        cursor: pointer;
      }
      .hits li:last-child { border-bottom: none; }
      .hits li:hover { background: rgba(255, 255, 255, 0.02); }
      .filename { font-weight: 500; }
      .uploaded { color: var(--vd-fg-muted); font-size: 0.8rem; }
      .muted { color: var(--vd-fg-muted); }
    `,
  ],
})
export class DocumentsPageComponent {
  protected readonly auth = inject(AuthStore);
  protected readonly store = inject(DocumentsStore);
  private readonly router = inject(Router);

  protected readonly selected = signal<DocumentSummary | null>(null);

  protected readonly heading = computed(() => {
    const id = this.store.selectedFolderId();
    if (!id) return 'All documents';
    const folder = this.store.folders().find((f) => f.id === id);
    return folder?.name ?? 'Folder';
  });

  constructor() {
    void this.store.loadFolders();
    void this.store.loadDocuments();

    // Poll while any visible doc is still pending OCR. Bounded to one timer
    // at a time; stops automatically when nothing's in flight.
    const destroyRef = inject(DestroyRef);
    let timer: ReturnType<typeof setInterval> | null = null;
    effect(() => {
      const anyPending = this.store
        .documents()
        .some((d) => d.ocrStatus === 'PENDING' || d.ocrStatus === 'PROCESSING');
      if (anyPending && timer === null) {
        timer = setInterval(() => void this.store.loadDocuments(), 3000);
      } else if (!anyPending && timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    });
    destroyRef.onDestroy(() => {
      if (timer !== null) clearInterval(timer);
    });
  }

  open(doc: DocumentSummary): void {
    this.selected.set(doc);
  }

  openFromHit(hit: SearchHit): void {
    // Cast hit to DocumentSummary shape — they share the relevant fields.
    // ocrStatus comes from a follow-up getDetail call inside the viewer.
    this.selected.set({
      id: hit.documentId,
      filename: hit.filename,
      mimeType: hit.mimeType,
      sizeBytes: hit.sizeBytes,
      folderId: hit.folderId,
      status: 'READY',
      documentType: hit.documentType,
      uploadedAt: hit.uploadedAt,
      ocrStatus: null,
    } as unknown as DocumentSummary);
  }

  signOut(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
