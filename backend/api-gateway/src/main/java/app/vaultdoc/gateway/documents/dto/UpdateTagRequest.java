package app.vaultdoc.gateway.documents.dto;

/** Absent (null) fields are left unchanged; {@code color: ""} clears the colour. */
public record UpdateTagRequest(String name, String color) {}
