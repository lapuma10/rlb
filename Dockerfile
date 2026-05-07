# RLB bot container — host-built jar variant.
#
# Build flow:
#   ./gradlew :client:shadowJar
#   cp -r ~/.runelite/cache       seed/
#   cp -r ~/.runelite/jagexcache  seed/
#   docker build -t rlb-bot .
#
# See entrypoint.sh for the runtime startup sequence (Xvfb + x11vnc +
# optional proxychains wrap + java).

FROM eclipse-temurin:17-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
      xvfb x11vnc \
      proxychains4 \
      libxext6 libxrender1 libxtst6 libxi6 libxrandr2 \
      fontconfig fonts-dejavu \
       x11-utils \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Built once on host with `./gradlew :client:shadowJar`. Glob keeps
# the COPY working as the version bumps.
COPY runelite-client/build/libs/client-*-shaded.jar /app/client.jar

# Public game caches captured once on host. Skipping these means
# every fresh container does a 200MB+ download from Jagex on first
# launch (slow + a fingerprint pattern). They are NOT account-
# specific; the same seed is fine for every account.
COPY seed/cache       /seed/.runelite/cache
COPY seed/jagexcache  /seed/.runelite/jagexcache

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENV DISPLAY=:99
EXPOSE 5900
VOLUME ["/root/.runelite"]
ENTRYPOINT ["/entrypoint.sh"]
