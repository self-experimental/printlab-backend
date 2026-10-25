#!/bin/sh
set -eu
umask 077
for file in experiments.json research-models.json; do
    if [ ! -e "$PRINTLAB_DATA_DIR/$file" ]; then
        cp "/app/seed/$file" "$PRINTLAB_DATA_DIR/$file"
    fi
done
exec /opt/java/bin/java -Xms128m -Xmx1g -XX:ActiveProcessorCount=3 \
    -Djava.awt.headless=true -jar /app/app.jar
