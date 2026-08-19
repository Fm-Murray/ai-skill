#!/bin/bash

# AI Skill Orchestration System - Startup Script
# Requires Java 8 (SpringBoot 2.7 is not compatible with Java 26+)

# Try to find Java 8
JAVA8_HOME=""
if [ -d "/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home" ]; then
    JAVA8_HOME="/Library/Java/JavaVirtualMachines/jdk-1.8.jdk/Contents/Home"
elif [ -n "$JAVA_HOME_8" ]; then
    JAVA8_HOME="$JAVA_HOME_8"
fi

if [ -n "$JAVA8_HOME" ]; then
    export JAVA_HOME="$JAVA8_HOME"
    echo "Using Java 8: $JAVA8_HOME"
else
    echo "Warning: Java 8 not found in standard location."
    echo "If you have Java 8, set JAVA_HOME before running this script."
    echo "Example: JAVA_HOME=/path/to/jdk8 ./run.sh"
fi

# Set the project directory
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# Compile and run
echo "Starting AI Skill Orchestration System..."
echo "Application will be available at: http://localhost:8080"
echo ""

mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xms512m -Xmx2048m -Dfile.encoding=UTF-8"
