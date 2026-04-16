# ── Build stage ──
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Cache Maven dependencies
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B 2>/dev/null || true

# Copy source and build
COPY src ./src
RUN ./mvnw package -DskipTests -B 2>/dev/null || mvn package -DskipTests -B

# ── Runtime stage ──
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Create non-root user
RUN groupadd -r agreemint && useradd -r -g agreemint agreemint
RUN mkdir -p /home/agreemint/.agreemint/storage && chown -R agreemint:agreemint /home/agreemint

COPY --from=build /app/target/*.jar app.jar

USER agreemint
ENV PORT=8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s \
  CMD curl -f http://localhost:$PORT/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java -Dserver.port=$PORT -jar app.jar"]
