FROM eclipse-temurin@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0

WORKDIR /opt/yueji
COPY --chown=10001:10001 backend/target/yueji-mall-0.1.0-SNAPSHOT.jar /opt/yueji/app.jar
COPY --chown=10001:10001 backend/docker-entrypoint.sh /usr/local/bin/yueji-entrypoint
RUN mkdir -p /var/lib/yueji/files /var/log/yueji /tmp/yueji && chown -R 10001:10001 /var/lib/yueji /var/log/yueji /tmp/yueji \
    && chmod 0555 /usr/local/bin/yueji-entrypoint

USER 10001:10001
EXPOSE 8080 9081
ENTRYPOINT ["/usr/local/bin/yueji-entrypoint"]
