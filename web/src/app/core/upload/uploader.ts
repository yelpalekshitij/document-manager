import { Observable } from 'rxjs';

export interface UploadProgress {
  loaded: number;
  total: number;
  percent: number;
}

export interface UploadResult {
  status: number;
  ok: boolean;
}

/**
 * PUT a Blob to a presigned URL with progress events. Uses raw XHR because
 * HttpClient's `reportProgress` is awkward to wire when bypassing interceptors
 * — and we MUST bypass them here (no Authorization header on presigned URLs).
 */
export function presignedPut(
  url: string,
  body: Blob,
  contentType: string,
): Observable<UploadProgress | UploadResult> {
  return new Observable((subscriber) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url, true);
    xhr.setRequestHeader('Content-Type', contentType);

    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        subscriber.next({
          loaded: event.loaded,
          total: event.total,
          percent: Math.round((event.loaded / event.total) * 100),
        });
      }
    };

    xhr.onload = () => {
      const ok = xhr.status >= 200 && xhr.status < 300;
      subscriber.next({ status: xhr.status, ok });
      if (ok) {
        subscriber.complete();
      } else {
        subscriber.error(new Error(`Upload failed with status ${xhr.status}`));
      }
    };

    xhr.onerror = () => subscriber.error(new Error('Network error during upload'));
    xhr.onabort = () => subscriber.error(new Error('Upload aborted'));

    xhr.send(body);

    return () => xhr.abort();
  });
}

export function sha256Hex(buffer: ArrayBuffer): Promise<string> {
  return crypto.subtle.digest('SHA-256', buffer).then((hash) => {
    const bytes = new Uint8Array(hash);
    let hex = '';
    for (let i = 0; i < bytes.length; i++) {
      hex += bytes[i].toString(16).padStart(2, '0');
    }
    return hex;
  });
}
