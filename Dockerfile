# Stage 1: build the mkdocs site.
FROM python:3.12-slim AS docs
WORKDIR /docs
COPY docs/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY docs/ ./
RUN mkdocs build --strict --site-dir /site

# Stage 2: build the application jar. The mkdocs site is dropped into
# arbiter-webapp's static resources before `mvn package` so it ends up
# packaged inside the jar at classpath:/static/docs/.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache Maven dependencies on the per-module poms first so source-only
# changes don't re-trigger the dependency download.
COPY pom.xml ./
COPY arbiter-domain/pom.xml arbiter-domain/pom.xml
COPY arbiter-platform/pom.xml arbiter-platform/pom.xml
COPY arbiter-webapp/pom.xml arbiter-webapp/pom.xml
RUN mvn -B -ntp -q -e -DskipTests dependency:go-offline || true

COPY arbiter-domain arbiter-domain
COPY arbiter-platform arbiter-platform
COPY arbiter-webapp arbiter-webapp

# Bake the rendered mkdocs site into the webapp's classpath as static/docs.
COPY --from=docs /site arbiter-webapp/src/main/resources/static/docs

RUN mvn -B -ntp -e -DskipTests package

# Stage 3: runtime image.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /build/arbiter-webapp/target/arbiter-webapp-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
