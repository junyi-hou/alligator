#!/usr/bin/env bash
# Integration test script for Alligator LSP multiplexer This script spawns a mock
# client, alligator, and multiple mock servers, then connects them together via pipes to
# test the multiplexing behavior.  This script should always be ran from the project
# root, or via clj -T:build mock-test suite-name Usage: test_runner.sh suite-name

set -e
set -o pipefail

MOCK_TEST_FOLDER=$(dirname "$0")

# Check number of arguments
if [ "$#" -ne 1 ]; then
    echo "Error: Exactly one argument is required"
    echo "Usage: $0 <suite-name>"
    echo "Available suites: $(find "$MOCK_TEST_FOLDER" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | tr '\n' ' ')"
    exit 1
fi

# Resolve CLIENT and CONFIG_FILE from the first positional argument
SUITE_NAME="$1"

# Check if the suite directory exists
if [ ! -d "$MOCK_TEST_FOLDER/$SUITE_NAME" ]; then
    echo "Error: Unknown test suite '$SUITE_NAME'"
    echo "Available suites: $(find "$MOCK_TEST_FOLDER" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | tr '\n' ' ')"
    exit 1
fi

CLIENT="mock.${SUITE_NAME}.client"
CONFIG_FILE="$MOCK_TEST_FOLDER/$SUITE_NAME/config.toml"

# Create FIFO
FIFO=$(mktemp -u)
mkfifo "$FIFO"
trap "rm -f '$FIFO'" EXIT INT TERM

# Run tests
clj -M:mock-test -m "$CLIENT" < "$FIFO" | clj -M -m alligator.core "$CONFIG_FILE" > "$FIFO"

exit 0
