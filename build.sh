#!/bin/bash
./gradlew fatJar
rm ./klogs
echo '#!/usr/bin/java -jar' >> klogs
cat build/libs/klogs-1.0-SNAPSHOT-standalone.jar >> klogs
chmod +x klogs