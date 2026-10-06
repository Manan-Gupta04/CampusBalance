# ---- Build stage: compile the Spring Boot app (API + web pages) with Maven ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Download dependencies first, so they're cached between builds when only the code changes
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src src
RUN mvn -q -B clean package -DskipTests

# ---- Run stage: just the JRE + the built jar, keeps the final image small ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# "Today" (wellness logs, streaks, semester end dates) follows this time zone
ENV TZ=Asia/Kolkata
EXPOSE 8080

# Let the heap use most of a small container's memory (Render free tier = 512 MB)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
