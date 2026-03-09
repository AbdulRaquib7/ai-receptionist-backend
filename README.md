# Impekable Front Desk AI Receptionist

## Overview

Impekable Front Desk is an AI-powered receptionist system. See the [Application Overview](ApplicationOverview.md) file for more details about the app itself and [Getting Started](GettingStarted.md) for setup instructions.

## Building and Running

This is a Maven-based Spring Boot application.

If using the command line:
```
brew install mvn
mvn validate
mvn compile
```

Then
```
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Alternatively, use IntelliJ to manage Maven install and running Spring Boot.

## Running Tests

To run tests
```
mvn test
```
## Running Postgres

This will create a Postgres Docker container and database for the app running on port 5432
```
make docker-postgres
```
