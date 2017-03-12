#!/bin/sh

find ./src -type f -print0 | xargs -0 sed -i '' 's/net.sourceforge.opencamera/com.github.jamsinclair.owcamera/g'
find ./src -type f -print0 | xargs -0 sed -i '' 's/Open Camera/OW Camera for Pebble/g'

perl -pi -e 's/net.sourceforge.opencamera/com.github.jamsinclair.owcamera/g' AndroidManifest.xml

mkdir src/main/java/com
mkdir src/main/java/com/github
mkdir src/main/java/com/github/jamsinclair

mv src/main/java/net/sourceforge/opencamera src/main/java/com/github/jamsinclair
mv src/main/java/com/github/jamsinclair/opencamera src/main/java/com/github/jamsinclair/owcamera

rm -rf net/sourceforge
