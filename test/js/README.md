# JS unit tests

Pure-function tests for the static JS in `src/main/resources/static/`.

Run all:  `node --test test/js/*.test.js`

(Node 18–26: pass a file glob, not the bare `test/js/` directory — newer Node treats a
directory argument to `--test` as a single file path and errors.)

These cover only side-effect-free helpers (aggregation, chart-series building).
HTML-render functions are verified manually against the local app at http://localhost:8090.
