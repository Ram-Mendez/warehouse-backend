# Project Codex specialists

Verified with installed `codex-cli 0.159.1`. Project-scoped standalone `.codex/agents/*.toml` files use the officially supported `name`, `description`, and `developer_instructions` fields. Reviewer also defaults to `sandbox_mode = "read-only"`. Model and reasoning settings inherit from the parent.

Source: [official custom-agent documentation](https://learn.chatgpt.com/docs/agent-configuration/subagents#custom-agents).

`.codex/config.toml` enables delegation and caps concurrent children at two (excluding the main agent). Four available responsibilities do not mean four agents run on every task. AGENTS.md remains the repository contract and specifies when to delegate. No global Codex settings are changed.

Start a **new Codex session from this repository** to load the definitions. Project configuration must be trusted/enabled by your Codex installation; if agents do not appear, inspect project trust/configuration rather than assuming delegation worked. `codex agents` browses sessions; it is not a custom-agent creation command.

Example PowerShell commands (each starts the main agent, which delegates the bounded task and integrates the result):

```powershell
codex exec 'Delegate to database_flyway to review V1-V3 constraints and the movement audit trigger. Read-only investigation; report concrete SQL findings.'
codex exec 'Delegate to security_auth to inspect the current auth schema and configuration against AGENTS.md. Report implemented behavior and missing requirements; make no edits.'
codex exec 'Delegate to integration_concurrency to assess tests for stock decrement races, transfer rollback, and actor audit snapshots. Do not invent APIs or add tests for unimplemented services; report gaps.'
codex exec 'Delegate to reviewer_architecture to review the current backend flow and diff. Return actionable findings with file and symbol references; make no edits.'
```

Normal work can simply say: `Implement the requested warehouse feature; follow AGENTS.md and use only specialists whose domain materially applies.` The main agent selects specialists using their descriptions and the delegation rules. This is instruction-driven delegation, not an automatic hook or guaranteed trigger on every matching word. Small changes stay with the main agent.

Specialists receive distinct file ownership before edits; the reviewer never edits. Independent read-only investigations can overlap. Maven tests in one worktree/shared database must run serially. After delegated implementation, the main agent reviews the combined result and runs `.\mvnw.cmd test`.

To inspect the loaded project instructions and delegation limit without running a model task:

```powershell
codex debug prompt-input 'Check available project specialists'
```

Names in AGENTS.md alone do not prove custom-agent discovery. A real session should expose the four custom names in its spawn tool; ask it to select `reviewer_architecture` for a bounded read-only check when verifying runtime delegation.

Parent runtime permission overrides can supersede a child's sandbox default; the reviewer also has explicit read-only instructions. No additional agents, duplicate skills, model pins, background orchestration scripts, hooks, or application changes are included.
