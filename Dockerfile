# Build do Quarkus com Java 21
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp package -DskipTests

# Runtime: Java + Tesseract OCR em inglês e português
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng tesseract-ocr-por \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home appuser
WORKDIR /app
COPY --from=build --chown=appuser:appuser /workspace/target/*-runner.jar ./app.jar
USER 10001
ENV QUARKUS_HTTP_HOST=0.0.0.0
ENV PORT=8080
ENV OCR_COMMAND=tesseract
ENV OCR_LANGUAGE=eng+por
EXPOSE 8080
CMD ["java", "-jar", "app.jar", "--no-window"]
