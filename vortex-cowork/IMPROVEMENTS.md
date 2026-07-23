# Code Review Improvements

Changes applied on top of the uploaded `vortex-cowork-main` snapshot (see the
"pristine baseline" commit for the original).

## Bug fixes

- **Deleted conversations reappeared after relaunch** (`AppContext.jsx`): the
  save effect only ran when at least one conversation had messages, so deleting
  the last chat never rewrote `conversations.json`. Saving now always runs once
  settings are loaded (and still waits for streaming to finish).
- **`run_bash` lost output on failure** (`main/index.js`): a non-zero exit made
  `execAsync` throw, so the model got a bare error with no stdout and a fake
  `exit_code`. Failures now return real `stdout`, `stderr`, and the actual exit
  code; timeouts are reported with the configured limit. Also added a 10 MB
  output buffer.
- **Approval modal could hang the agent loop forever**: if the renderer
  reloaded while a confirmation was pending, the promise never resolved.
  Approvals now auto-deny after 5 minutes.
- **Silent stop at the 15-iteration cap**: the loop now tells the user it hit
  the tool-iteration limit instead of just ending the turn.
- **Malformed tool-call arguments were silently run as `{}`**: the model now
  gets an explicit "invalid JSON" tool result so it can retry.
- **MCP handshake**: the client now sends `notifications/initialized` after
  `initialize` (required by the MCP spec; some servers drop requests without
  it), and the `tools/list` timeout cleans up its pending callback.

## Security

- **Shell injection in PDF attachment extraction**: `pdftotext` was invoked via
  `exec` with the filename interpolated into a shell string — a file named
  `x"$(rm -rf ~)".pdf` would execute. Now uses `execFile` with an argument
  array.
- **Renderer file-explorer IPC could escape the workspace**: `fs:read/write/
  list/open/delete` resolved `..` paths but never enforced the boundary —
  notably `fs:delete` does a recursive force-delete. These handlers now reject
  any path outside the selected workspace (the model's tools keep their
  existing approval-gate flow for escapes).

## Features / UX

- **Stop button**: a per-conversation `AbortController` in the main process
  (`chat:stop` IPC) cancels the API stream and halts the agentic loop between
  tool calls; pending approval prompts for that conversation are auto-denied.
  The send button becomes a stop button while streaming, and partial output is
  kept as a normal message instead of surfacing as an error.
- **Smarter auto-scroll**: the chat only follows the stream when you're already
  near the bottom, so scrolling up to reread isn't fought by incoming tokens.
  Switching conversations still jumps to the latest message.

## Refactor

- The ~60-line tool-execution block that was duplicated between the streaming
  and non-streaming branches of `chat:send` is now a single shared
  `processToolCalls()` helper (which is also where the abort/malformed-args
  handling lives).

## Round 2

- **`read_file` can now actually see images** (fixes "my read_file tool only
  gave me raw binary bytes" on PNGs): image files return a base64 data URL and
  are injected into the transcript as a vision (`image_url`) message — the same
  mechanism `computer_screenshot` already used, now generalized in
  `buildToolResultEntries`. PDF/DOCX/XLSX/zip reads also route through the
  attachment extractor, so the model gets extracted text instead of mojibake.
  Images over 5 MB are rejected with a hint to resize first. The tool-call
  panel in the UI already renders `dataUrl` results as an inline image.
- **Drag & drop attachments**: drop files anywhere on the chat panel to attach
  them (same pipeline as the paperclip button — PDF, DOCX, XLSX, images, code,
  zip). Uses `webUtils.getPathForFile` in the preload with a `File.path`
  fallback, shows a drop overlay, and blocks Electron's default
  navigate-to-dropped-file behavior. Attachment state moved from `InputBar` up
  to `ChatPanel` so the whole panel can be the drop zone.

## Known issues not addressed (candidates for a follow-up)

- Tool calls/results are shown live but not persisted into the API transcript
  for later turns — after a restart the model loses that context.
- The API key is stored in plaintext `settings.json`; Electron's `safeStorage`
  would be a better fit.
- `APPEND_TOKEN` rebuilds the whole conversation array per token — fine at
  current sizes, worth virtualizing if chats get long.
- MCP startup uses a fixed 800 ms sleep instead of waiting for the
  `initialize` response.
