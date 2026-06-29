import { Component, inject, signal } from '@angular/core';

import { DocumentsStore } from './documents.store';

@Component({
  selector: 'vd-upload-zone',
  standalone: true,
  template: `
    <div
      class="zone"
      [class.dragging]="dragging()"
      (dragover)="onDragOver($event)"
      (dragleave)="onDragLeave($event)"
      (drop)="onDrop($event)"
    >
      <input
        type="file"
        multiple
        #fileInput
        (change)="onFileInput($event)"
        style="display:none"
      />
      <p class="prompt">
        Drag files here or
        <button type="button" class="link" (click)="fileInput.click()">browse</button>
      </p>
      <p class="hint">PDF, JPG, PNG, WebP, HEIC, TIFF, DOCX — up to 50 MB</p>
    </div>

    @if (store.uploads().length > 0) {
      <ul class="uploads">
        @for (u of store.uploads(); track u.id) {
          <li [class.error]="u.status === 'error'" [class.done]="u.status === 'done'">
            <div class="row">
              <span class="filename">{{ u.filename }}</span>
              <span class="status">{{ statusLabel(u.status) }}</span>
              <button
                type="button"
                class="dismiss"
                aria-label="Dismiss"
                (click)="store.dismissUpload(u.id)"
              >×</button>
            </div>
            <div class="bar"><div class="fill" [style.width.%]="u.percent"></div></div>
            @if (u.error) {
              <p class="err">{{ u.error }}</p>
            }
          </li>
        }
      </ul>
    }
  `,
  styles: [
    `
      .zone {
        border: 2px dashed var(--vd-border);
        border-radius: 12px;
        padding: 1.75rem;
        text-align: center;
        background: var(--vd-bg-card);
        transition: border-color 0.2s, background 0.2s;
      }
      .zone.dragging {
        border-color: var(--vd-accent);
        background: rgba(88, 166, 255, 0.05);
      }
      .prompt { margin: 0 0 0.35rem; }
      .hint { margin: 0; color: var(--vd-fg-muted); font-size: 0.85rem; }
      .link {
        background: transparent;
        border: none;
        color: var(--vd-accent);
        cursor: pointer;
        text-decoration: underline;
        font: inherit;
        padding: 0;
      }
      .uploads { list-style: none; padding: 0; margin: 1rem 0 0; display: grid; gap: 0.6rem; }
      .uploads li {
        background: var(--vd-bg-card);
        border: 1px solid var(--vd-border);
        border-radius: 8px;
        padding: 0.65rem 0.85rem;
      }
      .uploads li.done { border-color: var(--vd-success); }
      .uploads li.error { border-color: var(--vd-danger); }
      .row { display: flex; align-items: center; gap: 0.6rem; font-size: 0.9rem; }
      .filename { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .status { color: var(--vd-fg-muted); font-size: 0.78rem; text-transform: uppercase; }
      .dismiss {
        background: transparent; border: none; color: var(--vd-fg-muted);
        cursor: pointer; font-size: 1.1rem; line-height: 1; padding: 0 0.25rem;
      }
      .bar {
        height: 3px; background: var(--vd-border); border-radius: 2px; margin-top: 0.5rem; overflow: hidden;
      }
      .fill { height: 100%; background: var(--vd-accent); transition: width 0.15s linear; }
      .err { color: var(--vd-danger); margin: 0.4rem 0 0; font-size: 0.8rem; }
    `,
  ],
})
export class UploadZoneComponent {
  protected readonly store = inject(DocumentsStore);
  protected readonly dragging = signal(false);

  protected statusLabel(status: string): string {
    switch (status) {
      case 'preparing': return 'Hashing…';
      case 'uploading': return 'Uploading';
      case 'finalizing': return 'Finalizing';
      case 'done': return 'Done';
      case 'error': return 'Failed';
      default: return status;
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const files = event.dataTransfer?.files;
    if (files) this.upload(files);
  }

  onFileInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files) this.upload(input.files);
    input.value = '';
  }

  private async upload(files: FileList): Promise<void> {
    for (let i = 0; i < files.length; i++) {
      await this.store.uploadFile(files[i]);
    }
  }
}
