FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy the executable jar from the build stage
COPY ./arbiter-webapp/target/arbiter-webapp-1.0.0-SNAPSHOT.jar app.jar

# Expose the port the app runs on
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
