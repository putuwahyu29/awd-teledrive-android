#!/bin/bash

# Awd TeleDrive Interactive Release Script (Bash)

GRADLE_FILE="app/build.gradle.kts"

# 1. Read current version info
CURRENT_CODE=$(grep "versionCode =" $GRADLE_FILE | sed 's/[^0-9]*//g')
CURRENT_NAME=$(grep "versionName =" $GRADLE_FILE | sed 's/.*"\(.*\)".*/\1/')

echo -e "\e[36m--- TeleDrive Release Manager ---\e[0m"
echo "Current Version Name: $CURRENT_NAME"
echo "Current Version Code: $CURRENT_CODE"
echo "---------------------------------"

# 2. Ask for new version
read -p "Enter New Version Name (e.g. 1.2.0) [$CURRENT_NAME]: " NEW_NAME
NEW_NAME=${NEW_NAME:-$CURRENT_NAME}

DEFAULT_CODE=$((CURRENT_CODE + 1))
read -p "Enter New Version Code (e.g. 3) [$DEFAULT_CODE]: " NEW_CODE
NEW_CODE=${NEW_CODE:-$DEFAULT_CODE}

echo -e "\n\e[33mUpdating to Version $NEW_NAME ($NEW_CODE)...\e[0m"

# 3. Update build.gradle.kts
# Use a different delimiter for sed since we have quotes
sed -i "s/versionCode = .*/versionCode = $NEW_CODE/" $GRADLE_FILE
sed -i "s/versionName = .*/versionName = \"$NEW_NAME\"/" $GRADLE_FILE

echo -e "\e[32mSuccessfully updated app/build.gradle.kts\e[0m\n"

# 4. Git Automation
read -p "Commit, Tag, and Push to GitHub? (y/n): " CONFIRM_GIT
if [[ $CONFIRM_GIT == "y" || $CONFIRM_GIT == "Y" ]]; then
    git add $GRADLE_FILE
    git commit -m "chore: bump version to $NEW_NAME ($NEW_CODE)"

    TAG_NAME="v$NEW_NAME"
    echo -e "\e[36mCreating tag $TAG_NAME...\e[0m"
    git tag -a "$TAG_NAME" -m "Release $TAG_NAME"

    echo -e "\e[36mPushing to GitHub...\e[0m"
    git push origin main
    git push origin "$TAG_NAME"

    echo -e "\n\e[32mDone! GitHub Actions will now build the release APK.\e[0m"
else
    echo -e "\e[90mSkipped Git commands. Remember to commit and tag manually if needed.\e[0m"
fi

read -p "Press enter to exit..."
