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
import { DocumentSummary, SearchHit, Tag, TagRef } from '../../core/api/types';
import { TagChipsComponent } from '../tags/tag-chips.component';
import { TagFilterComponent } from '../tags/tag-filter.component';
import { TagManagerComponent } from '../tags/tag-manager.component';
import { TagsStore } from '../tags/tags.store';

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
    TagChipsComponent,
    TagFilterComponent,
    TagManagerComponent,
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
        <vd-tag-filter (manage)="managingTags.set(true)" />
        <div class="spacer"></div>
        <nav class="links">
          <a routerLink="/">Dashboard</a>
          <button type="button" class="link" (click)="signOut()">Sign out</button>
        </nav>
      </aside>

      <main>
        <div class="topbar">
          <div>
            <h2>{{ heading() }}</h2>
            <p class="filter-summary" aria-live="polite">{{ filterSummary() }}</p>
          </div>
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
                    <vd-tag-chips [documentId]="h.documentId" [tags]="hitTags(h)" />
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

    @if (managingTags()) {
      <vd-tag-manager (close)="managingTags.set(false)" />
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
      .filter-summary { margin: 0.2rem 0 0; color: var(--vd-fg-muted); font-size: 0.8rem; min-height: 1em; }

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
  protected readonly managingTags = signal(false);
  private readonly tags = inject(TagsStore);

  /** "Tagged: tax + paid" — announced politely so filter changes are audible. */
  protected readonly filterSummary = computed(() => {
    const byId = this.tags.byId();
    const names = this.store.selectedTagIds()
      .map((id) => byId.get(id)?.name)
      .filter((n): n is string => !!n);
    return names.length ? `Tagged: ${names.join(' + ')}` : '';
  });

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

  /** Search hits carry tag ids only; names and colours come from the tag store. */
  hitTags(hit: SearchHit): TagRef[] {
    const byId = this.tags.byId();
    return (hit.tagIds ?? [])
      .map((id) => byId.get(id))
      .filter((t): t is Tag => !!t)
      .map((t) => ({ id: t.id, name: t.name, color: t.color }));
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
      tags: this.hitTags(hit),
    } as unknown as DocumentSummary);
  }

  signOut(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
