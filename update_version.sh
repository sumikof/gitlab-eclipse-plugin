#!/bin/bash

# Function to display usage information
usage() {
    echo "Usage: $0 <new_version>"
    echo "Updates the version number in pom.xml and build.gradle.kts files."
    echo ""
    echo "Arguments:"
    echo "  <new_version>  The new version number to set (e.g., 0.3.1)"
    echo ""
    echo "Example:"
    echo "  $0 0.3.1"
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

# Check if a version number is provided
if [ $# -eq 0 ]; then
    usage
    exit 1
fi

# The new version number
NEW_VERSION=$1

# Validate version number format
if ! [[ $NEW_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?$ ]]; then
    echo "Error: Invalid version number format. Please use semantic versioning (e.g., 1.2.3 or 1.2.3-alpha.1)"
    exit 1
fi

# Update pom.xml
echo "Updating pom.xml..."
POM_AWK_COMMAND='/<version>[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?<\/version>/ && !f {sub(/<version>[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?<\/version>/, "<version>'"$NEW_VERSION"'</version>"); f=1} 1'
update_file "pom.xml" "$POM_AWK_COMMAND" || exit 1

# Update update-site/pom.xml
echo "Updating pom.xml..."
POM_AWK_COMMAND='/<version>[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?<\/version>/ && !f {sub(/<version>[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?<\/version>/, "<version>'"$NEW_VERSION"'</version>"); f=1} 1'
update_file "update-site/pom.xml" "$POM_AWK_COMMAND" || exit 1

# Update feature/pom.xml
echo "Updating pom.xml..."
POM_AWK_COMMAND='/<version>[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?<\/version>/ && !f {sub(/<version>[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?<\/version>/, "<version>'"$NEW_VERSION"'</version>"); f=1} 1'
update_file "feature/pom.xml" "$POM_AWK_COMMAND" || exit 1

# Update build.gradle.kts
echo "Updating build.gradle.kts..."
GRADLE_AWK_COMMAND='/version = / {sub(/\"[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?\"/, "\"'"$NEW_VERSION"'\""); f=1} /ext\["bundleVersion"\] = / {sub(/\"[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+)?(\+[0-9A-Za-z-]+)?/, "\"'"$NEW_VERSION"'"); f=1} 1'
update_file "build.gradle.kts" "$GRADLE_AWK_COMMAND" || exit 1

echo "Version update completed successfully. New version: $NEW_VERSION"
