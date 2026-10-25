# Build stage: compiles the application and runs no tests (the tests run in the normal Maven build, not here).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

# Runtime stage: a JRE only, and a user without privileges.
FROM eclipse-temurin:21-jre
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app
COPY --from=build /build/target/claims-management-platform.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
