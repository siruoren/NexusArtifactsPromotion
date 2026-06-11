#!/bin/bash
cd $(dirname $0)
set -e
export PATH="/usr/local/opt/openjdk@8/bin:$PATH" 
# 从 pom.xml 提取项目本身的 artifactId 和 version
ARTIFACT_ID=$(grep -m1 '<artifactId>' pom.xml | sed 's/.*<artifactId>\([^<]*\)<\/artifactId>.*/\1/')
PROJECT_VERSION=$(grep -m1 '<version>' pom.xml | sed 's/.*<version>\([^<]*\)<\/version>.*/\1/')
PROJECT_NAME="${ARTIFACT_ID}"

# Nexus plugin deploy directory (default for Karaf-based Nexus 3)
NEXUS_HOME="${NEXUS_HOME:-/opt/sonatype/nexus}"
NEXUS_PLUGIN_DIR="${NEXUS_HOME}/system/com/nexus/artifacts/${ARTIFACT_ID}/${PROJECT_VERSION}"

MVN_OPTS="-Denforcer.skip=true -DskipTests"

print_usage() {
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  build        Build the plugin (default)"
    echo "  clean        Clean build artifacts"
    echo "  package      Build and package the OSGi bundle"
    echo "  deploy       Build and deploy to local Maven repo"
    echo "  install      Build and install to Nexus plugin directory"
    echo "  test         Run unit tests"
    echo "  help         Show this help message"
    echo ""
    echo "Options:"
    echo "  -v, --verbose   Enable verbose output"
    echo "  -d, --debug     Enable debug mode"
    echo ""
    echo "Environment Variables:"
    echo "  NEXUS_HOME      Nexus installation directory (default: /opt/sonatype/nexus)"
}

clean() {
    echo "Cleaning build artifacts..."
    mvn clean
}

build() {
    echo "Building $PROJECT_NAME v${PROJECT_VERSION}..."
    mvn compile $MVN_OPTS ${VERBOSE:+"-X"}
}

package() {
    echo "Packaging $PROJECT_NAME v${PROJECT_VERSION}..."
    mvn package $MVN_OPTS ${VERBOSE:+"-X"}

    JAR_FILE=$(find target -name "${PROJECT_NAME}-${PROJECT_VERSION}.jar" ! -name "*-sources" ! -name "*-javadoc" | head -1)
    if [ -f "$JAR_FILE" ]; then
        echo ""
        echo "Build successful!"
        echo "Plugin file: $JAR_FILE"
        echo "File size: $(du -h "$JAR_FILE" | awk '{print $1}')"

        # Verify OSGi manifest
        if jar tf "$JAR_FILE" | grep -q "META-INF/MANIFEST.MF"; then
            echo "OSGi bundle: Verified"
        fi
    else
        echo "Build failed - JAR file not found"
        exit 1
    fi
}

deploy() {
    echo "Deploying $PROJECT_NAME to local Maven repository..."
    mvn deploy $MVN_OPTS -DaltDeploymentRepository=local::default::file://$HOME/.m2/repository
}

install() {
    echo "Installing $PROJECT_NAME to Nexus plugin directory..."
    mvn package $MVN_OPTS ${VERBOSE:+"-X"}

    JAR_FILE=$(find target -name "${PROJECT_NAME}-${PROJECT_VERSION}.jar" ! -name "*-sources" ! -name "*-javadoc" | head -1)
    if [ ! -f "$JAR_FILE" ]; then
        echo "Build failed - JAR file not found"
        exit 1
    fi

    if [ ! -d "$NEXUS_PLUGIN_DIR" ]; then
        echo "Creating plugin directory: $NEXUS_PLUGIN_DIR"
        mkdir -p "$NEXUS_PLUGIN_DIR"
    fi

    cp "$JAR_FILE" "$NEXUS_PLUGIN_DIR/"
    echo "Plugin installed to: $NEXUS_PLUGIN_DIR/$(basename $JAR_FILE)"
    echo ""
    echo "IMPORTANT: Restart Nexus to load the plugin."
    echo "  sudo systemctl restart nexus"
}

test() {
    echo "Running tests for $PROJECT_NAME..."
    mvn test ${VERBOSE:+"-X"}
}

VERBOSE=0

if [ $# -eq 0 ]; then
    package
    exit 0
fi

while [[ $# -gt 0 ]]; do
    case $1 in
        build)
            build
            shift
            ;;
        clean)
            clean
            shift
            ;;
        package)
            package
            shift
            ;;
        deploy)
            deploy
            shift
            ;;
        install)
            install
            shift
            ;;
        test)
            test
            shift
            ;;
        -v|--verbose)
            VERBOSE=1
            shift
            ;;
        -d|--debug)
            set -x
            shift
            ;;
        help|--help|-h)
            print_usage
            exit 0
            ;;
        *)
            echo "Unknown command: $1"
            print_usage
            exit 1
            ;;
    esac
done
