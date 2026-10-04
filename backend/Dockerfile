FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# JVM flags for a 1 GiB, 1 vCPU Cloud Run container (hosting plan section 06).
# With no flags the heap is capped at 25% of RAM, about 256 MB, and the rest sits unused.
#   MaxRAMPercentage=65  heap ~665 MB. The other ~35% is metaspace, code cache, thread stacks and
#                        JVM internals, which are not optional. Do not push this toward 85 or set
#                        -Xmx900m: the heap stays in bounds, the process does not, and the kernel
#                        OOM-kills it with no Java stack trace. Raise to 70 only if GC runs hot.
#   UseSerialGC          G1's worker threads cost more than they return on one vCPU.
#   TieredStopAtLevel=1  faster warmup, lower peak throughput. Right trade at this traffic.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=65", \
    "-XX:+UseSerialGC", \
    "-XX:TieredStopAtLevel=1", \
    "-jar", "app.jar"]
