# Alligator

An LSP (Language Server Protocol) Multiplexer written in Clojure. It allows connecting multiple LSP servers and multiplexing their messages to a single client.

## Features

- Multiplex multiple LSP servers simultaneously.
- Configurable server capabilities.
- Command-line or config-file based configuration.

## Installation

Download the prebuild binary from the release page and put it in your `$PATH`.

### Build From Source

#### Prerequisites

GraalVM 21

#### Build Native Binary

```bash
clojure -T:build binary
```

And the binary is located at `target/alligator-OS-ARCH-VERSION`.

## Usage

### Command Line
You can specify servers directly via the command line:
```bash
alligator -- "pyright-langserver --stdio" --default -- "ruff server" -c definition-provider
```

### Configuration File
Alternatively, use a `config.toml` file:
```bash
alligator -c path/to/config.toml
```

## Development

### Running Tests
Run all unit tests:
```bash
clojure -M:test -m cognitect.test-runner -e :mock
```

Run mock integration tests:
```bash
clojure -M:test -m cognitect.test-runner -d test/alligator/mock/
```

### Linting and Formatting
Check for linting errors:
```bash
clojure-lsp diagnostics
```

Format the codebase:
```bash
clojure-lsp format
```

Clean up namespaces:
```bash
clojure-lsp clean-ns
```
