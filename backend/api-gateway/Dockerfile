# VaultDoc API runtime image.
#
# Build the boot jar first, then the image (context = this directory):
#   cd backend && ./gradlew :api-gateway:bootJar
#   docker build -t vaultdoc/api-gateway backend/api-gateway
#
# Tesseract runs as a child process of the JVM (CliTesseractRunner) — the
# image ships the CLI plus the English and Hindi language packs that the
# default vaultdoc.ocr.languages=eng+hin expects. Add more packs here
# (tesseract-ocr-tam, -tel, -ben, -mar, -ara, -chi-sim, …) as languages are enabled.
FROM eclipse-temurin:25-jre-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-eng \
        tesseract-ocr-hin \
    && rm -rf /var/lib/apt/lists/* \
    && tesseract --list-langs

RUN groupadd --system vaultdoc && useradd --system --gid vaultdoc --home /app vaultdoc
WORKDIR /app

# Per-job OCR scratch dirs (decrypted document bytes) live here, owner-only.
RUN mkdir -p /app/ocr-tmp && chown vaultdoc:vaultdoc /app/ocr-tmp && chmod 700 /app/ocr-tmp
ENV OCR_TEMP_DIR=/app/ocr-tmp

# Skip the "-plain" (non-executable) jar if the jar task also produced one.
COPY build/libs/ /tmp/libs/
RUN find /tmp/libs -name 'api-gateway-*.jar' ! -name '*-plain.jar' -exec mv {} /app/app.jar \; \
    && rm -rf /tmp/libs && test -f /app/app.jar

USER vaultdoc
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
