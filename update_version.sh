#!/bin/bash

# Function to display usage information
usage() {
    echo "Usage: $0 [--prepare-release] <new_version>"
    echo "Updates the version number in pom.xml, build.gradle.kts, feature.xml and category.xml files."
    echo ""
    echo "Arguments:"
    echo "  <new_version>  The new version number to set (e.g., 0.3.1)"
    echo ""
    echo "Options:"
    echo "  --prepare-release  Prepare for a release by removing .qualifier suffix for the feature Jar"
    echo ""
    echo "Example:"
    echo "  $0 0.3.1"
    echo "  $0 --prepare-release 0.3.1"
    echo ""
    echo "Note: You must provide a new version number as an argument."
}

# Function to update file with awk
update_file() {
    local file=$1
    local awk_command=$2
    local temp_file="${file}.temp"

    if ! awk "$awk_command" "$file" > "$temp_file"; then
        echo "Error: Failed to update $file"
        rm -f "$temp_file"
        return 1
    fi

    if ! mv "$temp_file" "$file"; then
        echo "Error: Failed to replace $file with updated version"
        rm -f "$temp_file"
        return 1
    fi

    echo "Successfully updated $file"
}

# The new version number (e.g., 0.1.1)
SEMANTIC_VERSION=
# The next planned version number (e.g., 0.1.2 if the input if 0.1.1)
NEXT_SEMANTIC_VERSION=
# The maven version number  (e.g., 0.1.1-SNAPSHOT)
MAVEN_VERSION=
# The maven tycho version number  (e.g., 0.1.1.qualifier)
TYCHO_VERSION=
# If we are preparing a release
PREPARE_RELEASE=false

while [[ $# -gt 0 ]]; do
  case $1 in
    --prepare-release)
      PREPARE_RELEASE=true
      shift
      ;;
    *)
      SEMANTIC_VERSION=$1
      shift
      ;;
  esac
done

if [ -z "$SEMANTIC_VERSION" ]; then
    echo "Error: Version number is required."
    usage
    exit 1
fi

# Validate version number format
if ! [[ $SEMANTIC_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?$ ]]; then
    echo "Error: Invalid version number format. Please use semantic versioning (e.g., 1.2.3)"
    exit 1
fi

if [[ "$PREPARE_RELEASE" == "true" ]]; then
  CURRENT_VERSION=''
  CURRENT_VERSION=$(awk '/version = "[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?"/ {print $3}' build.gradle.kts)

  if [[ $CURRENT_VERSION != "\"$SEMANTIC_VERSION-SNAPSHOT\"" ]]; then
    echo "Error: Cannot prepare release for version \"$SEMANTIC_VERSION\" because it's different from the current snapshot version $CURRENT_VERSION."
    exit 1
  fi
fi

if [[ "$PREPARE_RELEASE" = "false" ]]; then
    MAVEN_VERSION="$SEMANTIC_VERSION-SNAPSHOT"
    TYCHO_VERSION="$SEMANTIC_VERSION.qualifier"
else
    MAVEN_VERSION="$SEMANTIC_VERSION"
    TYCHO_VERSION="$SEMANTIC_VERSION"
fi

# Update pom.xml
echo "Updating pom.xml..."
POM_AWK_COMMAND='/<version>[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?<\/version>/ && !f {sub(/<version>[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?<\/version>/, "<version>'"$MAVEN_VERSION"'</version>"); f=1} 1'
update_file "pom.xml" "$POM_AWK_COMMAND" || exit 1

# Update update-site/pom.xml
echo "Updating update-site/pom.xml..."
POM_AWK_COMMAND='/<version>[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?<\/version>/ && !f {sub(/<version>[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?<\/version>/, "<version>'"$MAVEN_VERSION"'</version>"); f=1} 1'
update_file "update-site/pom.xml" "$POM_AWK_COMMAND" || exit 1

# Update feature/pom.xml
echo "Updating feature/pom.xml..."
POM_AWK_COMMAND='/<version>[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?<\/version>/ && !f {sub(/<version>[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?<\/version>/, "<version>'"$MAVEN_VERSION"'</version>"); f=1} 1'
update_file "feature/pom.xml" "$POM_AWK_COMMAND" || exit 1

# Update feature/feature.xml
echo "Updating feature/feature.xml"
FEATURE_AWK_COMMAND=''
if [[ "$PREPARE_RELEASE" = "true" ]]; then
    # If we're preparing for a release, we only need to remove the .qualifier from the feature version.
    FEATURE_AWK_COMMAND='/version="[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?\.?(qualifier)?"/ && !f {sub(/version="[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?\.?(qualifier)?"/, "version=\"'"$SEMANTIC_VERSION"'\""); f=1} 1'
else
    # If we're not preparing a release, we need to increment all versions in the feature.xml.
    FEATURE_AWK_COMMAND='/version="[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?\.?(qualifier)?"/ {gsub(/version="[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?\.?(qualifier)?"/, "version=\"'"$TYCHO_VERSION"'\"")} 1'
fi
update_file "feature/feature.xml" "$FEATURE_AWK_COMMAND" || exit 1

# Update update-site/category.xml
echo "Updating update-site/category.xml"
CATEGORY_AWK_VERSION="$TYCHO_VERSION"
CATEGORY_AWK_URL="$TYCHO_VERSION.jar"
if [[ "$PREPARE_RELEASE" = "true" ]]; then
    CATEGORY_AWK_VERSION="$SEMANTIC_VERSION"
    CATEGORY_AWK_URL="$SEMANTIC_VERSION.jar"
fi
CATEGORY_AWK_COMMAND='/version=/{sub(/\"[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?\.?(qualifier)?"/, "\"'"$CATEGORY_AWK_VERSION"'\""); f=1} /url="features\/com\.gitlab\.eclipse\.feature_/{sub(/[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?\.?(qualifier)?\.jar/, "'"$CATEGORY_AWK_URL"'"); f=1} 1'
update_file "update-site/category.xml" "$CATEGORY_AWK_COMMAND" || exit 1

# Update swtbot/pom.xml version tag
echo "Updating swtbot/pom.xml version tag..."
POM_AWK_COMMAND='/<version>[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?<\/version>/ && !f {sub(/<version>[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?<\/version>/, "<version>'"$MAVEN_VERSION"'</version>"); f=1} 1'
update_file "swtbot/pom.xml" "$POM_AWK_COMMAND" || exit 1

# Update swtbot/pom.xml plugin.version.range
if [[ "$PREPARE_RELEASE" = "false" ]]; then
  echo "Updating swtbot/pom.xml plugin.version.range..."
  IFS="." read -ra VERSION_PARTS <<<"$SEMANTIC_VERSION"
  NEXT_SEMANTIC_VERSION="${VERSION_PARTS[0]}.${VERSION_PARTS[1]}.$((VERSION_PARTS[2] + 1))"
  POM_AWK_COMMAND='/<plugin.version.range>/ && !f {sub(/<plugin.version.range>\[[0-9]+\.[0-9]+\.[0-9]+,[0-9]+\.[0-9]+\.[0-9]+/, "<plugin.version.range>['$SEMANTIC_VERSION','$NEXT_SEMANTIC_VERSION'"); f=1} 1'
  update_file "swtbot/pom.xml" "$POM_AWK_COMMAND" || exit 1
fi

# Update build.gradle.kts
echo "Updating build.gradle.kts..."
GRADLE_AWK_COMMAND='/version = / {sub(/\"[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?\"/, "\"'"$MAVEN_VERSION"'\""); f=1} /ext\["bundleVersion"\] = / {sub(/\"[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?/, "\"'"$SEMANTIC_VERSION"'"); f=1} 1'
update_file "build.gradle.kts" "$GRADLE_AWK_COMMAND" || exit 1

echo "Version update completed successfully. New version: $SEMANTIC_VERSION"
