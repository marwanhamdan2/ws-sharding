# build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

## download dependencies
RUN ./gradlew dependencies --no-daemon

## Copy source and build the shadowjar
COPY src src
RUN ./gradlew shadowJar --no-daemon

RUN ls -la /app/build/libs/

## runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# copy the fat-jar from the build stage
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]