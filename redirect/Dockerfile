# syntax=docker/dockerfile:1
# Redirect service (crm-redirect). Build context is the repository root:
#   docker build -f redirect/Dockerfile -t tcrm/crm-redirect .

FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /src
# config/ holds the Checkstyle rules and license overrides referenced by the POM (../config).
COPY config/ config/
COPY redirect/ redirect/
# Tests, formatting and coverage gates run in CI (mvn verify); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -f redirect/pom.xml -DskipTests package \
    && cp redirect/target/crm-redirect-*.jar /crm-redirect.jar

FROM eclipse-temurin:21.0.12.1_1-jre
RUN groupadd --system --gid 10001 tcrm \
    && useradd --system --uid 10001 --gid tcrm --no-create-home --shell /usr/sbin/nologin tcrm
WORKDIR /app
COPY --from=build /crm-redirect.jar /app/app.jar
# Third-party license notices of every deliverable (ADR-006, spec 07 section 3).
COPY backend/THIRD-PARTY-NOTICES /licenses/backend.txt
COPY redirect/THIRD-PARTY-NOTICES /licenses/redirect.txt
COPY admin-ui/THIRD-PARTY-NOTICES /licenses/admin-ui.txt
COPY kol-portal/THIRD-PARTY-NOTICES /licenses/kol-portal.txt
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
