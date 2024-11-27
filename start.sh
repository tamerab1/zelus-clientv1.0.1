MAVEN_OPTS="-ea" ./mvnw --offline -pl runelite-client compile exec:java -Dexec.mainClass="net.runelite.client.RuneLite" -Dexec.arguments="--debug,--developer-mode"
