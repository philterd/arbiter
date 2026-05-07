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
COPY arbiter-core/pom.xml arbiter-core/pom.xml
COPY arbiter-philter-client/pom.xml arbiter-philter-client/pom.xml
COPY arbiter-data/pom.xml arbiter-data/pom.xml
COPY arbiter-service/pom.xml arbiter-service/pom.xml
COPY arbiter-api/pom.xml arbiter-api/pom.xml
COPY arbiter-webapp/pom.xml arbiter-webapp/pom.xml
RUN mvn -B -ntp -q -e -DskipTests dependency:go-offline || true

COPY arbiter-core arbiter-core
COPY arbiter-philter-client arbiter-philter-client
COPY arbiter-data arbiter-data
COPY arbiter-service arbiter-service
COPY arbiter-api arbiter-api
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
