# Codex event fixtures (snapshot-derived; CLI not installed on device yet)

Source of truth fetched 2026-10-28 (official openai/codex repo, MIT):
- sdk/typescript/src/events.ts — thread.started {thread_id}, turn.started,
  turn.completed {usage{input_tokens, output_tokens, ...}}, turn.failed
  {error{message}}, item.started/updated/completed, error.
- sdk/typescript/src/items.ts — agent_message{text, id}, reasoning{text, id},
  command_execution{command, aggregated_output, exit_code?, status},
  file_change{changes[{path, kind}], status}, mcp_tool_call{server, tool,
  arguments, status, result?/error?}, web_search, todo_list, error.
- https://developers.openai.com/codex/noninteractive — `codex exec --json`,
  resume via `codex exec resume <SESSION_ID>` (exact --json ordering unverified).

The resolver maps full item values into deltas per item id (like opencode).
