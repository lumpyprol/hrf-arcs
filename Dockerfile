FROM eclipse-temurin:17-jdk-jammy

RUN curl -sfL "https://raw.githubusercontent.com/sbt/sbt/develop/sbt" -o /usr/local/bin/sbt \
    && chmod +x /usr/local/bin/sbt

WORKDIR /app

COPY scala-js-dom-reduced ./scala-js-dom-reduced
COPY haunt-roll-fail ./haunt-roll-fail
COPY good-game ./good-game

RUN cd scala-js-dom-reduced && sbt publishLocal
RUN cd haunt-roll-fail && sbt fastOptJS
RUN cd good-game && sbt compile

ENV ARCS_URL=http://localhost:7070
ENV ARCS_CDN=http://localhost:7070/hrf/
ENV ARCS_PORT=7070

EXPOSE 7070

COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]
