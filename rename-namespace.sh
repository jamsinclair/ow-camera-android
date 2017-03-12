#!/bin/sh

export LC_CTYPE=C
export LANG=C

find ./app/src/main/java -type f -print0 | xargs -0 sed -i '' 's/net.sourceforge.opencamera/com.github.jamsinclair.owcamera/g'
find ./app/src/main/rs -type f -print0 | xargs -0 sed -i '' 's/net.sourceforge.opencamera/com.github.jamsinclair.owcamera/g'
find ./app/src/main/java -type f -print0 | xargs -0 sed -i '' 's/Open Camera/OW Camera for Pebble/g'
find ./app/src/main/res/values* -type f -print0 | xargs -0 sed -i '' 's/Open Camera/OW Camera for Pebble/g'

perl -pi -e 's/net.sourceforge.opencamera/com.github.jamsinclair.owcamera/g' ./app/src/main/AndroidManifest.xml
perl -pi -e 's/net.sourceforge.opencamera/com.github.jamsinclair.owcamera/g' ./app/build.gradle

mkdir ./app/src/main/java/com
mkdir ./app/src/main/java/com/github
mkdir ./app/src/main/java/com/github/jamsinclair

mv ./app/src/main/java/net/sourceforge/opencamera ./app/src/main/java/com/github/jamsinclair
mv ./app/src/main/java/com/github/jamsinclair/opencamera ./app/src/main/java/com/github/jamsinclair/owcamera

rm -rf ./app/src/main/java/net
