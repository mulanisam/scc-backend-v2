#### Stage 1: Build the application
FROM openjdk:17.0

COPY target/backend-0.0.1-SNAPSHOT.jar backend-0.0.1-SNAPSHOT.jar
 
ENTRYPOINT ["java","-jar","/backend-0.0.1-SNAPSHOT.jar"]