#!/bin/sh

export LC_CTYPE=C
export LANG=C

find ./app/src/main/java -type f -print0 | xargs -0 sed -i '' 's/net.sourceforge.opencamera/com.github.jamsinclair.owcamera2/g'
find ./app/src/main/kotlin -type f -print0 | xargs -0 sed -i '' 's/net.sourceforge.opencamera/com.github.jamsinclair.owcamera2/g'
find ./app/src/main/res -type f -print0 | xargs -0 sed -i '' 's/net.sourceforge.opencamera/com.github.jamsinclair.owcamera2/g'
find ./app/src/main/java -type f -print0 | xargs -0 sed -i '' 's/Open Camera/OW Camera 2 for Pebble/g'
find ./app/src/main/res/values* -type f -print0 | xargs -0 sed -i '' 's/Open Camera/OW Camera 2 for Pebble/g'

perl -pi -e 's/net.sourceforge.opencamera/com.github.jamsinclair.owcamera2/g' ./app/src/main/AndroidManifest.xml
perl -pi -e 's/net.sourceforge.opencamera/com.github.jamsinclair.owcamera2/g' ./app/build.gradle

mkdir -p ./app/src/main/java/com/github/jamsinclair
mkdir -p ./app/src/main/kotlin/com/github/jamsinclair

mv ./app/src/main/java/net/sourceforge/opencamera ./app/src/main/java/com/github/jamsinclair
mv ./app/src/main/java/com/github/jamsinclair/opencamera ./app/src/main/java/com/github/jamsinclair/owcamera2

mv ./app/src/main/kotlin/net/sourceforge/opencamera ./app/src/main/kotlin/com/github/jamsinclair
mv ./app/src/main/kotlin/com/github/jamsinclair/opencamera ./app/src/main/kotlin/com/github/jamsinclair/owcamera2

rm -rf ./app/src/main/java/net
rm -rf ./app/src/main/kotlin/net
