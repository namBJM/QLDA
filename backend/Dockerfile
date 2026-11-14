FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN mvn -Dmaven.test.skip=true package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/slms-m01-1.0.0.jar app.jar
RUN mkdir -p /app/data/evidence && chown -R 10001:10001 /app
USER 10001
ENTRYPOINT ["java","-jar","app.jar"]
