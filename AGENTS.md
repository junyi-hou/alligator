# Alligator LSP Multiplexer - Agent Guidelines

This document provides instructions for agentic coding agents operating in this repository.

## Testing
We use `clojure.test` with the Cognitect Labs test runner.

- **Run all unit tests:**
  ```bash
  clojure -M:test -m cognitect.test-runner -e :mock

- **Run mock integration tests:**
  ```bash
  clojure -M:test -m cognitect.test-runner -d test/alligator/mock/
  ```
### Linting and Formatting
We use `clojure-lsp` for linting and formatting.

- **Check diagnostics (Linting):**
  ```bash
  clojure-lsp diagnostics
  ```

- **Format code:**
  ```bash
  clojure-lsp format --dry
  ```
  *To apply changes:* `clojure-lsp format`

- **Clean up namespaces:**
  ```bash
  clojure-lsp clean-ns --dry
  ```
  *To apply changes:* `clojure-lsp clean-ns`

---

## Code Style Guidelines

### Namespaces and Imports
- **Naming:** Use `alligator.<module>` (e.g., `alligator.router`).
- **Imports:** 
  - Use `:require` with `:as` for most dependencies.
  - Use `:refer` only for common test functions (`deftest`, `is`, etc.) or internal DSLs.
  - Keep requirements sorted alphabetically.

```clojure
(ns alligator.example
   (:require [alligator.multiplexer :as mux]
             [clojure.core.async :as async]
             [clojure.java.io :as io]
             [taoensso.timbre :as timbre]))
```

### Architecture & Patterns
- **Concurrency:** Primary concurrency model is `clojure.core.async`. Use `go`, `go-loop`, and channels for message passing.
- **Message Dispatch:** LSP methods are handled via `defmulti` in `alligator.methods`.
  - `process-client-message`: Client -> Servers.
  - `process-server-message`: Servers -> Client.
- **Dynamic Loading:** Handlers in `src/alligator/methods/` are dynamically loaded at startup. When adding a new method handler, create a new file in that directory.
- **State:** Use `atoms` for shared state (e.g., `alligator.states`). Keep state minimal and centralized.

### Naming Conventions
- **Functions/Variables:** `kebab-case`.
- **Private Functions:** Use `defn-` or `^:private` metadata.
- **Predicates:** Suffix with `?` (e.g., `valid-request?`).
- **Impure Functions:** Suffix with `!` if they have side effects (e.g., `load-handlers!`).

###  Error Handling
- Use `try/catch` for localized error handling.
- Use `ex-info` and `ex-data` to provide context in exceptions.
- Log errors using the `clojure.tools.logging` namespace.

### Types and Coercion
- We use `jsonrpc4clj` for JSON-RPC message handling.
- Use `jsonrpc4clj.coercer` to identify message types (:request, :notification, :response, etc.).

---

## Agent Interaction
- **Proactiveness:** When adding a new LSP method handler, ensure you add both the logic in `src/alligator/methods/<method>.clj` and corresponding tests in `test/alligator/<method>_test.clj`.
- **Verification:** Always run unit tests after modifications. If the change affects routing, run the `basic` mock test.
- **Context:** Refer to `src/alligator/methods/default.clj` for example handler implementations.

---

## Standards
- **Formatting:** We use `clojure-lsp format`. Follow standard Clojure indentation (2 spaces). No trailing whitespace.
- **Comments:** Prefer clear function names over comments. Use docstrings for public API functions.
- **Linting:** We use `clojure-lsp diagnostics`. Follow the existing codebase patterns strictly.
