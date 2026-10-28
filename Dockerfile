# Stage 1: build the mkdocs site.
FROM python:3.12-slim AS docs
WORKDIR /docs
COPY docs/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY docs/ ./
RUN mkdocs build --strict --site-dir /site

# Stage 2: build the application jar. The mkdocs site is dropped into
# philter-workspace-webapp's static resources before `mvn package` so it ends up
# packaged inside the jar at classpath:/static/docs/.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache Maven dependencies on the per-module poms first so source-only
# changes don't re-trigger the dependency download.
COPY pom.xml ./
COPY philter-workspace-domain/pom.xml philter-workspace-domain/pom.xml
COPY philter-workspace-platform/pom.xml philter-workspace-platform/pom.xml
COPY philter-workspace-webapp/pom.xml philter-workspace-webapp/pom.xml
RUN mvn -B -ntp -q -e -DskipTests dependency:go-offline || true

COPY philter-workspace-domain philter-workspace-domain
COPY philter-workspace-platform philter-workspace-platform
COPY philter-workspace-webapp philter-workspace-webapp

# Bake the rendered mkdocs site into the webapp's classpath as static/docs.
COPY --from=docs /site philter-workspace-webapp/src/main/resources/static/docs

RUN mvn -B -ntp -e -DskipTests package

# Stage 3: runtime image.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /build/philter-workspace-webapp/target/philter-workspace-webapp-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
