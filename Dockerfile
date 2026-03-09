# Stage 1: Build
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Download dependencies first to leverage Docker cache
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests

# Stage 2: Run
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/ai-receptionists-backend-0.0.1-SNAPSHOT.jar app.jar

# Cloud Run sets the PORT environment variable. 
# Spring Boot automatically maps the PORT env var to server.port.
EXPOSE 8080

# Using -Djava.security.egd=file:/dev/./urandom is a common practice for Java apps in containers to speed up startup times by providing a non-blocking source of entropy.
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
