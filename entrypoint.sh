#!/bin/sh

# Override local.properties to point to container SDK
if [ -n "$ANDROID_SDK_ROOT" ]; then
    echo "sdk.dir=$ANDROID_SDK_ROOT" > /workspace/local.properties
fi

# Execute gradlew with all arguments
cd /workspace
exec ./gradlew "$@"
