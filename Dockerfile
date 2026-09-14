# Build stage
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY src ./src
RUN javac -d out src/Main.java

# Run stage (small JRE)
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/out ./out
COPY public ./public
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-cp", "out", "Main"]
