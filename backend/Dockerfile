# Build: gera o fast-jar (target/quarkus-app) com Maven + JDK 25.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp package -DskipTests

# Runtime: JRE 25 enxuto; o fast-jar exige o diretório quarkus-app inteiro (lib/, app/, quarkus/).
FROM eclipse-temurin:25-jre
WORKDIR /deployments
COPY --from=build /build/target/quarkus-app/ /deployments/
EXPOSE 8080
CMD ["java", "-jar", "quarkus-run.jar"]
