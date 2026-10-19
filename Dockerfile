FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B package -DskipTests

FROM alpine:latest
RUN apk update && apk add --no-cache openjdk21
WORKDIR /app
COPY --from=build /app/target/*.jar backretos.jar
EXPOSE 8080
CMD ["java", "-jar", "backretos.jar"]