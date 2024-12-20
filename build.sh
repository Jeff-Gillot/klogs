#!/bin/sh
./gradlew clean fatJar
rm ./klogs
echo '#!java -jar' >> klogs
cat build/libs/klogs-1.0-SNAPSHOT-standalone.jar >> klogs
chmod +x klogs