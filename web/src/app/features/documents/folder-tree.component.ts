import { Component, computed, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';

import { DocumentsStore } from './documents.store';
import { FolderNode } from '../../core/api/types';

interface FolderTreeNode {
  node: FolderNode;
  children: FolderTreeNode[];
  depth: number;
}

@Component({
  selector: 'vd-folder-tree',
  standalone: true,
  imports: [NgTemplateOutlet],
  template: `
    <nav class="tree">
      <button
        type="button"
        class="row all-row"
        [class.selected]="store.selectedFolderId() === null"
        (click)="store.selectFolder(null)"
      >
        <span class="icon">📂</span>
        <span class="name">All documents</span>
      </button>

      @for (n of tree(); track n.node.id) {
        <ng-container *ngTemplateOutlet="folderRow; context: { $implicit: n }" />
      }

      @if (showCreate()) {
        <form class="new-folder" (submit)="onCreate($event)">
          <input
            type="text"
            placeholder="New folder name"
            [value]="newName()"
            (input)="newName.set($any($event.target).value)"
            autofocus
          />
          <div class="actions">
            <button type="submit" class="primary">Create</button>
            <button type="button" class="link" (click)="cancelCreate()">Cancel</button>
          </div>
        </form>
      } @else {
        <button type="button" class="add" (click)="startCreate()">+ New folder</button>
      }

      <ng-template #folderRow let-n>
        <button
          type="button"
          class="row"
          [class.selected]="store.selectedFolderId() === n.node.id"
          [style.padding-left.rem]="0.75 + n.depth * 0.9"
          (click)="store.selectFolder(n.node.id)"
        >
          <span class="icon">{{ n.node.icon ?? '📁' }}</span>
          <span class="name">{{ n.node.name }}</span>
          @if (n.node.system) {
            <span class="badge">system</span>
          }
        </button>
        @for (child of n.children; track child.node.id) {
          <ng-container *ngTemplateOutlet="folderRow; context: { $implicit: child }" />
        }
      </ng-template>
    </nav>
  `,
  styles: [
    `
      .tree {
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
      }
      .row {
        all: unset;
        display: flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.4rem 0.75rem;
        border-radius: 6px;
        cursor: pointer;
        font-size: 0.9rem;
        color: var(--vd-fg);
      }
      .row:hover { background: rgba(255, 255, 255, 0.03); }
      .row.selected { background: rgba(88, 166, 255, 0.12); color: var(--vd-accent); }
      .icon { font-size: 0.95rem; }
      .name { flex: 1; }
      .badge {
        font-size: 0.65rem;
        text-transform: uppercase;
        color: var(--vd-fg-muted);
        background: rgba(255, 255, 255, 0.04);
        padding: 0.1rem 0.35rem;
        border-radius: 4px;
      }
      .add {
        background: transparent;
        border: 1px dashed var(--vd-border);
        color: var(--vd-fg-muted);
        padding: 0.4rem 0.75rem;
        margin-top: 0.5rem;
        border-radius: 6px;
        cursor: pointer;
        text-align: left;
        font-size: 0.85rem;
      }
      .add:hover { color: var(--vd-accent); border-color: var(--vd-accent); }
      .new-folder { margin-top: 0.5rem; display: flex; flex-direction: column; gap: 0.4rem; }
      .new-folder input {
        background: var(--vd-bg);
        border: 1px solid var(--vd-border);
        color: var(--vd-fg);
        padding: 0.4rem 0.6rem;
        border-radius: 6px;
        font: inherit;
      }
      .actions { display: flex; gap: 0.5rem; }
      .primary {
        background: var(--vd-accent);
        color: #0e1116;
        border: none;
        border-radius: 6px;
        padding: 0.35rem 0.8rem;
        font: inherit;
        cursor: pointer;
      }
      .link {
        background: transparent;
        color: var(--vd-fg-muted);
        border: none;
        padding: 0.35rem 0.4rem;
        font: inherit;
        cursor: pointer;
      }
    `,
  ],
})
export class FolderTreeComponent {
  protected readonly store = inject(DocumentsStore);

  protected readonly showCreate = signal(false);
  protected readonly newName = signal('');

  protected readonly tree = computed<FolderTreeNode[]>(() => {
    const all = this.store.folders();
    const byParent = new Map<string | null, FolderNode[]>();
    for (const f of all) {
      const key = f.parentId ?? null;
      const list = byParent.get(key) ?? [];
      list.push(f);
      byParent.set(key, list);
    }
    const build = (parentId: string | null, depth: number): FolderTreeNode[] => {
      const items = byParent.get(parentId) ?? [];
      return items
        .slice()
        .sort((a, b) => (a.sortOrder - b.sortOrder) || a.name.localeCompare(b.name))
        .map((node) => ({ node, depth, children: build(node.id ?? null, depth + 1) }));
    };
    return build(null, 0);
  });

  startCreate(): void {
    this.showCreate.set(true);
    this.newName.set('');
  }

  cancelCreate(): void {
    this.showCreate.set(false);
    this.newName.set('');
  }

  async onCreate(event: Event): Promise<void> {
    event.preventDefault();
    const name = this.newName().trim();
    if (!name) return;
    const parentId = this.store.selectedFolderId() ?? undefined;
    await this.store.createFolder({ name, parentId });
    this.cancelCreate();
  }
}
