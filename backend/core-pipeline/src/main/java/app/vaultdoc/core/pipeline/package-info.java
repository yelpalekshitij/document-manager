/**
 * Shared messaging infrastructure for the document-processing pipeline.
 *
 * <p>Each stage (OCR, extraction, alerts) lives in its own module and
 * communicates via Redis Streams. This package owns the envelope types,
 * stream-name constants, and the producer/consumer plumbing.
 */
package app.vaultdoc.core.pipeline;
