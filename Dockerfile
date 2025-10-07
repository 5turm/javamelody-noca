# Basis-Image
FROM amazoncorretto:17-alpine

# Argument und Umgebungsvariable für Version
ARG JAVAMELODY_VERSION=1.99.3
ENV JAVAMELODY_VERSION=${JAVAMELODY_VERSION}

# Arbeitsverzeichnis
WORKDIR /opt/javamelody

# Optional: minimale Tools
RUN apk add --no-cache bash

# WAR-Datei kopieren – Name dynamisch per Versionsvariable
# Erwartet: javamelody-collector-server-${JAVAMELODY_VERSION}.war im Build-Verzeichnis
COPY javamelody-collector-server-${JAVAMELODY_VERSION}.war /opt/javamelody/javamelody-collector.war

# Speicherverzeichnis für Monitoring-Daten
RUN mkdir -p /opt/javamelody/storage

# Port freigeben
EXPOSE 8080

# Standard JVM-Optionen (können beim Start überschrieben werden)
ENV JAVA_OPTS="-Djavamelody.storage-directory=/opt/javamelody/storage"

# Startbefehl
CMD ["sh", "-c", "java $JAVA_OPTS -jar /opt/javamelody/javamelody-collector.war --httpPort=8080"]

