import { HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';

import { ApiError, ProblemDetails } from './types';

/**
 * RxJS error mapper: turns the raw {@link HttpErrorResponse} from Angular's
 * HttpClient into our {@link ApiError}, which carries the parsed RFC 7807
 * Problem body so screens can branch on `problem.type`.
 */
export function toApiError(err: unknown): Observable<never> {
  if (err instanceof HttpErrorResponse) {
    const problem = (err.error ?? null) as ProblemDetails | null;
    const message = problem?.detail ?? problem?.title ?? err.message ?? 'Network error';
    return throwError(() => new ApiError(err.status, problem, message));
  }
  return throwError(() => err);
}
