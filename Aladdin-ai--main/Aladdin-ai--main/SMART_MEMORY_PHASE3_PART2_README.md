# Phase 3 — Smart Memory (Part 2)

This document describes the Phase 3 / Part 2 additions to Aladdin's Smart
Memory subsystem. Part 1 (user profile, preferences, facts, contacts) is
preserved unchanged. Part 2 adds five new modular memory layers that are
fully integrated with the existing `MemoryManager`.

> ✅ All Part 1 APIs continue to work exactly as before — Part 2 is purely
> additive and config-gated.

---

## What's New

| Module | File | Purpose |
|--------|------|---------|
| Projects Memory | `aladdin_core/project_memory.py` | Track projects, goals, files, status |
| Locations Memory | `aladdin_core/location_memory.py` | Home, office, favorites, custom places |
| Reminders Memory | `aladdin_core/reminder_memory.py` | Persistent reminders with recurrence |
| Calendar Memory | `aladdin_core/calendar_memory.py` | Meetings, events, birthdays, appointments |
| Conversation Summaries | `aladdin_core/conversation_summary.py` | Automatic chat summarization for token efficiency |

Each layer:
- Has its own SQLite database (configurable path)
- Can be enabled/disabled independently in `config.yaml`
- Is exposed both directly (`mem.projects.*`) and via convenience helpers on
  `MemoryManager` (`mem.add_project(...)`, etc.)

---

## 1. Projects Memory (`ProjectMemory`)

Stores project records with names, descriptions, goals, status, related
files, tags and notes; supports archiving and automatic linking of
conversations to the projects they mention.

### Key APIs

```python
pid = mem.add_project(
    name="Aladdin v3",
    description="Smart memory upgrade",
    goals=["Add project memory", "Ship Phase 3 part 2"],
    related_files=["aladdin_core/project_memory.py"],
)

mem.projects.add_goal(pid, "Document everything")
mem.projects.add_file(pid, "SMART_MEMORY_PHASE3_PART2_README.md")
mem.update_project(pid, status="paused")
mem.archive_project(pid)

mem.search_projects("memory")
mem.list_projects(status="active")
mem.list_projects(include_archived=True)
```

### Automatic Conversation Linking

Every time you call `mem.record_message(...)`, the project memory scans the
message text for known project names (case-insensitive, names ≥ 3 chars)
and links the conversation id to each matching project. No manual tagging
required.

```python
mem.record_message("user", "Let's continue working on Aladdin v3 today",
                   conversation_id="chat-2026-06-19")
mem.projects.list_conversations(pid)
# -> [{"conversation_id": "chat-2026-06-19", "linked_at": ...}]
```

### Status values

`active` (default) · `paused` · `completed` · `archived` · `cancelled`

---

## 2. Locations Memory (`LocationMemory`)

Remembers home, office, frequently-visited places, favorites and custom
named locations. Coordinates are optional but enable nearest-location
search via the Haversine formula.

```python
mem.set_home(address="221B Baker Street, London", latitude=51.523, longitude=-0.158)
mem.set_office(address="221B Office")
mem.save_location("gym", address="Pulse Fitness", category="custom",
                  is_favorite=True)

mem.get_home()
mem.search_locations("baker")
mem.locations.frequently_visited(limit=3)
mem.locations.favorites()
mem.locations.nearest(latitude=51.52, longitude=-0.16, limit=3)
mem.locations.record_visit("gym")
```

Categories used internally: `home`, `office`, `custom` (extensible).

---

## 3. Reminders Memory (`ReminderMemory`)

Persistent reminders with due dates, completion tracking, priority and
**recurrence** (`none` / `daily` / `weekly` / `monthly` / `yearly`).

```python
rid = mem.add_reminder("Call mom", due_at="2026-06-20 18:00",
                       recurrence="weekly")
mem.complete_reminder(rid)     # for recurring reminders this rolls due_at forward
mem.list_reminders()           # pending only
mem.list_reminders(include_completed=True)
mem.overdue_reminders()
mem.update_reminder(rid, title="Call mom & dad", priority=3)
mem.delete_reminder(rid)
mem.reminders.due_within(seconds=3600)
```

Marking a recurring reminder completed automatically schedules the next
occurrence using its `recurrence_interval` (default 1).

---

## 4. Calendar Memory (`CalendarMemory`)

Stores meetings, events, birthdays, appointments and important dates.
Designed with future external calendar integrations in mind — every event
carries a `source` (default `"local"`) and optional `external_id`, so a
Google Calendar / iCal sync can upsert events without conflicts.

```python
mem.add_meeting("Team standup", start_at="2026-06-20 09:30",
                end_at="2026-06-20 10:00", location="Zoom")
mem.add_birthday("Alice", date="1992-04-12")
mem.calendar.add_appointment("Dentist", start_at="2026-07-02 11:00")
mem.calendar.add_important_date("Anniversary", date="2024-09-14")

mem.upcoming_events(days=14)
mem.events_on("2026-06-20")
mem.search_calendar("standup")
mem.calendar.birthdays()
mem.update_calendar_event(event_id, location="Office")
mem.delete_calendar_event(event_id)

# Future external sync hook
mem.calendar.upsert_external(
    source="google", external_id="abc123",
    title="Q3 Review", start_at="2026-09-30 14:00",
)
```

Event types: `meeting` · `event` · `birthday` · `appointment` · `important` · `other`.
Yearly recurrence (e.g. birthdays) is handled automatically in `events_on()`.

---

## 5. Conversation Summaries (`ConversationSummary`)

Automatic summarization of long conversations to reduce token usage in
LLM prompts and preserve long-running context.

```python
# Recording a message automatically triggers summarization when the
# configured threshold is reached:
mem.record_message("user", "Hi Aladdin",  conversation_id="chat-1")
mem.record_message("assistant", "Hello!", conversation_id="chat-1")
# ... after `summary_trigger_messages` (default 30), a summary is stored.

ctx = mem.get_conversation_context("chat-1", recent_messages=10)
# -> {"summary": "<compressed older context>", "recent": [...last 10 msgs...]}

mem.summarize_conversation("chat-1")     # force-summarize now
mem.get_conversation_summary("chat-1")   # latest summary record
```

### Pluggable summarizer

The default summarizer is a dependency-free extractive algorithm. To use
the local LLM (Ollama) instead:

```python
def llm_summarizer(messages, max_length):
    prompt = "Summarize this conversation in <= {} chars:\n{}".format(
        max_length,
        "\n".join(f"{m['role']}: {m['content']}" for m in messages),
    )
    return ollama_client.generate(prompt)[:max_length]

mem.set_conversation_summarizer(llm_summarizer)
```

---

## Architecture

```
aladdin_core/
├── memory_manager.py        # ← Central orchestrator (Part 1 + Part 2)
│
├── user_profile.py          # Part 1
├── preferences.py           # Part 1
├── facts.py                 # Part 1
├── contacts.py              # Part 1
│
├── project_memory.py        # Part 2 — NEW
├── location_memory.py       # Part 2 — NEW
├── reminder_memory.py       # Part 2 — NEW
├── calendar_memory.py       # Part 2 — NEW
└── conversation_summary.py  # Part 2 — NEW
```

The `MemoryManager` constructor reads the `MemoryCfg` flags and instantiates
each Part 2 layer only when its `*_enabled` flag is `True`. When disabled,
the corresponding attribute (`mem.projects`, `mem.locations`, …) is `None`
and the convenience helpers return safe no-op defaults (`None` / `[]` /
`False`) — so calling code never has to special-case configuration.

### Storage layout (defaults)

| Layer | File |
|-------|------|
| Profile / Preferences / Facts / Contacts | `data/aladdin_memory.sqlite` |
| Projects | `data/project_memory.sqlite` |
| Locations | `data/location_memory.sqlite` |
| Reminders | `data/reminder_memory.sqlite` |
| Calendar | `data/calendar_memory.sqlite` |
| Conversation summaries | `data/conversation_summary.sqlite` |

All paths are overridable in `config.yaml`.

---

## Configuration

Add the following block under `memory:` in `config.yaml` (already done by
this update — shown here for reference):

```yaml
memory:
  # ... existing Part 1 settings ...

  # Smart Memory Part 2 (Phase 3)
  project_memory_enabled: true
  project_memory_db_path: data/project_memory.sqlite

  location_memory_enabled: true
  location_memory_db_path: data/location_memory.sqlite

  reminder_memory_enabled: true
  reminder_memory_db_path: data/reminder_memory.sqlite

  calendar_memory_enabled: true
  calendar_memory_db_path: data/calendar_memory.sqlite

  conversation_summary_enabled: true
  conversation_summary_db_path: data/conversation_summary.sqlite
  summary_trigger_messages: 30      # auto-summarize after N new messages
  summary_max_length: 500           # max characters per summary
```

### Defaults

| Option | Default |
|--------|---------|
| `project_memory_enabled` | `true` |
| `location_memory_enabled` | `true` |
| `reminder_memory_enabled` | `true` |
| `calendar_memory_enabled` | `true` |
| `conversation_summary_enabled` | `true` |
| `summary_trigger_messages` | `30` |
| `summary_max_length` | `500` |

---

## Backward Compatibility

* The Voice Core (Parts 1 & 2) and AI Brain modules are **not modified**.
* Existing `MemoryManager` constructor signature (`MemoryManager(cfg)`) is
  unchanged; all Part 1 attributes (`profile`, `preferences`, `facts`,
  `contacts`) and methods continue to work identically.
* The new layers are only added; nothing is removed.
* If a `MemoryCfg` instance from older code lacks the new `*_enabled`
  flags, the manager defaults them to `True` (via a tolerant `_cfg()`
  helper), so old code continues to run.

---

## Validation

The following checks were performed when generating this update:

1. ✅ All new modules import cleanly under Python 3.
2. ✅ `aladdin_core.MemoryManager(MemoryCfg())` instantiates without error.
3. ✅ Each Part 2 layer round-trips data through SQLite (create → read →
   update → delete).
4. ✅ Disabling a flag in `MemoryCfg` removes that layer cleanly while
   the rest of the manager continues to function.
5. ✅ Smart Memory Part 1 calls produce identical results.
6. ✅ No temporary debug code left behind; no Part 1 functionality
   removed.


---

## Phase 3 Part 3 continuation

Part 3 extends this foundation with semantic retrieval infrastructure:
importance scoring, ranking, embeddings and a persistent vector store.
See [`SMART_MEMORY_PHASE3_PART3_README.md`](SMART_MEMORY_PHASE3_PART3_README.md).
