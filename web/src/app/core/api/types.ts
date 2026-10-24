// Re-exports from the generated client + a small custom error type.
//
// Keep this file as the single import site for app code, so when the generator
// output ever changes shape, only this file needs editing.

export {
  AuthResponse,
  RegistrationResponse,
  RegisterRequest,
  LoginRequest,
  RefreshRequest,
  VerifyEmailRequest,
  ResendVerificationRequest,
  MeResponse,
  HealthResponse,
  Problem,
  AccountStatus,
  InitiateUploadRequest,
  InitiateUploadResponse,
  FinalizeUploadRequest,
  DocumentSummary,
  DocumentDetail,
  DocumentListResponse,
  DocumentDownloadResponse,
  DocumentStatus,
  FolderNode,
  FolderTreeResponse,
  CreateFolderRequest,
  UpdateFolderRequest,
  SearchHit,
  SearchResponse,
  Tag,
  TagRef,
  TagListResponse,
  CreateTagRequest,
  UpdateTagRequest,
  SetDocumentTagsRequest,
  DocumentTagsResponse,
} from './generated';

export interface ProblemDetails {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  [extension: string]: unknown;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: ProblemDetails | null,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }

  /** True when the problem `type` matches the given URI. */
  is(type: string): boolean {
    return this.problem?.type === type;
  }
}

export const PROBLEM_UNVERIFIED_ACCOUNT = 'https://vaultdoc.app/problems/unverified-account';
export const PROBLEM_INVALID_VERIFICATION_TOKEN =
  'https://vaultdoc.app/problems/invalid-verification-token';
export const PROBLEM_VERIFICATION_RATE_LIMITED =
  'https://vaultdoc.app/problems/verification-rate-limited';
export const PROBLEM_EMAIL_ALREADY_IN_USE =
  'https://vaultdoc.app/problems/email-already-in-use';
export const PROBLEM_PASSKEY_CHALLENGE_EXPIRED =
  'https://vaultdoc.app/problems/passkey-challenge-expired';
export const PROBLEM_INVALID_PASSKEY_CREDENTIAL =
  'https://vaultdoc.app/problems/invalid-passkey-credential';
export const PROBLEM_PASSKEY_NOT_FOUND =
  'https://vaultdoc.app/problems/passkey-not-found';
export const PROBLEM_MIME_NOT_ALLOWED = 'https://vaultdoc.app/problems/mime-not-allowed';
export const PROBLEM_SIZE_EXCEEDED = 'https://vaultdoc.app/problems/size-exceeded';
export const PROBLEM_SIZE_MISMATCH = 'https://vaultdoc.app/problems/size-mismatch';
export const PROBLEM_UPLOAD_OBJECT_MISSING =
  'https://vaultdoc.app/problems/upload-object-missing';
export const PROBLEM_DOCUMENT_NOT_FOUND = 'https://vaultdoc.app/problems/document-not-found';
export const PROBLEM_FOLDER_NOT_FOUND = 'https://vaultdoc.app/problems/folder-not-found';
export const PROBLEM_TAG_NOT_FOUND = 'https://vaultdoc.app/problems/tag-not-found';
export const PROBLEM_TAG_NAME_CONFLICT = 'https://vaultdoc.app/problems/tag-name-conflict';
export const PROBLEM_TAG_LIMIT_REACHED = 'https://vaultdoc.app/problems/tag-limit-reached';
export const PROBLEM_INVALID_TAG = 'https://vaultdoc.app/problems/invalid-tag';
