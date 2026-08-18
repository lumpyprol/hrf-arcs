FROM eclipse-temurin:17-jdk-jammy

RUN curl -sfL "https://raw.githubusercontent.com/sbt/sbt/develop/sbt" -o /usr/local/bin/sbt \
    && chmod +x /usr/local/bin/sbt

WORKDIR /app

COPY scala-js-dom-reduced ./scala-js-dom-reduced
COPY haunt-roll-fail ./haunt-roll-fail
COPY good-game ./good-game

RUN cd good-game && sbt compile

# The haunt-roll-fail Scala source we have only goes up to 0.8.140; the
# client bundle actually served is vendored from hrf.im's 0.8.157 build
# (see haunt-roll-fail/vendor/) since we don't have source for it.
RUN mkdir -p /app/haunt-roll-fail/target/scala-2.13 \
    && cp /app/haunt-roll-fail/vendor/hrf-fastopt-0.8.157.js /app/haunt-roll-fail/target/scala-2.13/hrf-fastopt.js

ENV ARCS_URL=http://localhost:7070
ENV ARCS_CDN=http://localhost:7070/hrf/
ENV ARCS_PORT=7070

EXPOSE 7070

COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]
