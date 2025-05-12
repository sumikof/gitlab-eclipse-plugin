#!/bin/bash

MANIFEST_PATH="META-INF/MANIFEST.MF"
CHECKSUM_FILE="p2-libs/.checksum.md5"

create_checksum() {
  echo "Creating checksum for $MANIFEST_PATH..."
  md5sum "$MANIFEST_PATH" > "$CHECKSUM_FILE"
}

download_p2_dependencies() {
  echo "Cleaning p2 dependencies..."
  rm p2-libs/*.jar

  echo "Downloading p2 dependencies..."
  mvn org.apache.maven.plugins:maven-dependency-plugin:copy-dependencies -DoutputDirectory=p2-libs
}

if [ ! -f "$CHECKSUM_FILE" ]; then
  create_checksum
  download_p2_dependencies

  echo "Baseline created successfully for $MANIFEST_PATH at CHECKSUM_FILE."
else
  if md5sum -c "$CHECKSUM_FILE"; then
      echo "$MANIFEST_PATH verified: No changes detected."
  else
      echo "$MANIFEST_PATH has been modified!"

      create_checksum
      download_p2_dependencies
  fi
fi
