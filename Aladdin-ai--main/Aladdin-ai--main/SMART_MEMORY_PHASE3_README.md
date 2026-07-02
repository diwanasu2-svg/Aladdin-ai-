# Phase 3: Smart Memory Implementation (Part 1)

## Overview

Phase 3 introduces **comprehensive smart memory** to Aladdin, building on the Voice Core (Parts 1 & 2) and AI Brain (Phase 1 & 2). This implementation adds:

1. **Long-Term Memory** — Persistent storage across sessions
2. **User Profile** — Name, nickname, language, timezone, preferred assistant name
3. **Preferences** — Voice, theme, response style, units, time format
4. **Facts** — Favorite programming language, apps, frequently used commands
5. **Contacts** — Name, phone, email, relationship, nickname

## Architecture

### Modular Design

Each memory layer is a separate module with a clear interface:

```
memory_manager.py      # Central orchestrator
├── user_profile.py    # User information
├── preferences.py     # User preferences
├── facts.py          # Long-term facts
└── contacts.py       # Contact database
```

### Storage

All data is stored in **SQLite** (`data/aladdin_memory.sqlite`):
- **user_profile** table: Single-row profile information
- **preferences** table: Key-value preferences
- **facts** table: Categorized facts with importance levels
- **contacts** table: Full contact database with relationships

## Features

### 1. Long-Term Memory

- **Persistent Storage**: Automatically loads on startup, saves after updates
- **Modular Interface**: Support for future database backends (JSON, PostgreSQL, etc.)
- **Auto-Extraction**: Facts extracted from conversations
- **Categorization**: Facts organized by category (identity, preference, project, etc.)
- **Importance Levels**: Facts can have importance scores (1-5)

### 2. User Profile

```python
mem.profile.set_name("Alice")
mem.profile.get_name()                    # "Alice"
mem.profile.set_language("en")
mem.profile.set_timezone("America/New_York")
mem.profile.set_preferred_assistant_name("Genie")
```

**Fields:**
- `name`: Full name
- `nickname`: Nickname or alias
- `language`: Preferred language (e.g., 'en', 'es', 'fr')
- `timezone`: Timezone for time-based features
- `preferred_assistant_name`: What the user calls the assistant
- `bio`: Short biography

### 3. Preferences

```python
mem.preferences.set_voice("en_US-amy-medium")
mem.preferences.set_theme("dark")
mem.preferences.set_response_style("professional")
mem.preferences.set_units("metric")
mem.preferences.set_time_format("24h")
```

**Default Preferences:**
- `voice`: Voice model name (default: "en_US-amy-medium")
- `theme`: UI theme (default: "light")
- `response_style`: Response tone (default: "friendly")
- `units`: Measurement units (default: "metric")
- `time_format`: Time display format (default: "24h")
- `verbosity`: Response length (default: "normal")
- `auto_search`: Enable web search (default: True)
- `notifications_enabled`: Enable notifications (default: True)

### 4. Facts

```python
# Remember facts
mem.facts.remember("favorite_language", "Python", category="preferences", importance=5)
mem.facts.remember("favorite_app", "VS Code", category="tools", importance=3)

# Recall facts
mem.facts.recall("favorite_language")  # "Python"

# Search facts
mem.facts.search("Python")  # Returns all matching facts

# Get by category
mem.facts.get_by_category("preferences")  # {"favorite_language": "Python", ...}

# Update facts
mem.facts.update("favorite_language", "Rust", importance=5)

# Forget facts
mem.facts.forget("favorite_language")
```

**Categories:**
- `general`: General facts
- `identity`: Identity-related facts
- `preference`: User preferences
- `project`: Project information
- `tools`: Tools and apps
- `commands`: Frequently used commands

### 5. Contacts

```python
# Add contact
contact_id = mem.contacts.add(
    name="John Doe",
    phone="+1-555-0123",
    email="john@example.com",
    relationship="friend",
    nickname="Johnny"
)

# Get contact
mem.contacts.get_by_id(contact_id)

# Search contacts
mem.contacts.search("Johnny")  # Search by name or nickname
mem.contacts.get_by_phone("+1-555-0123")
mem.contacts.get_by_email("john@example.com")

# Get all contacts by relationship
mem.contacts.get_by_relationship("friend")

# Update contact
mem.contacts.update(contact_id, phone="+1-555-9999")

# Delete contact
mem.contacts.delete(contact_id)
```

## MemoryManager API

The central `MemoryManager` orchestrates all memory layers:

```python
from aladdin_core.memory_manager import MemoryManager
from aladdin_core.config import MemoryCfg

# Initialize
mem = MemoryManager(MemoryCfg(db_path="data/aladdin_memory.sqlite"))

# Profile
mem.set_user_name("Alice")
mem.get_user_name()
mem.get_profile()
mem.update_profile({"language": "es", "timezone": "UTC"})

# Preferences
mem.set_preference("voice", "en_US-ryan-high")
mem.get_preference("voice")
mem.update_preferences({"theme": "dark", "units": "imperial"})

# Facts
mem.remember_fact("favorite_color", "blue")
mem.recall_fact("favorite_color")
mem.update_fact("favorite_color", "red")
mem.forget_fact("favorite_color")

# Contacts
contact_id = mem.add_contact("Alice", phone="555-1234")
mem.update_contact(contact_id, email="alice@example.com")
mem.delete_contact(contact_id)

# Statistics
mem.get_summary()           # Comprehensive summary
mem.get_statistics()        # Memory statistics
mem.export_memory("backup.json")  # Export to JSON
```

## Integration with main.py

The `MemoryManager` is already integrated into `main.py` as the central memory system:

```python
# In main.py
from aladdin_core.memory_manager import MemoryManager

self.memory = MemoryManager(cfg.memory)

# Access memory layers
user_name = self.memory.get_user_name()
voice_pref = self.memory.get_preference("voice")
facts = self.memory.get_all_facts()
contacts = self.memory.get_all_contacts()
```

## Database Schema

### user_profile
```sql
CREATE TABLE user_profile (
    id                      INTEGER PRIMARY KEY CHECK (id = 1),
    name                    TEXT,
    nickname                TEXT,
    language                TEXT DEFAULT 'en',
    timezone                TEXT,
    preferred_assistant_name TEXT,
    bio                     TEXT,
    created_at              DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

### preferences
```sql
CREATE TABLE preferences (
    key                 TEXT PRIMARY KEY,
    value               TEXT NOT NULL,
    data_type           TEXT DEFAULT 'str',
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

### facts
```sql
CREATE TABLE facts (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    key                 TEXT NOT NULL UNIQUE,
    value               TEXT NOT NULL,
    category            TEXT DEFAULT 'general',
    importance          INTEGER DEFAULT 1,
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

### contacts
```sql
CREATE TABLE contacts (
    id                  TEXT PRIMARY KEY,
    name                TEXT NOT NULL,
    phone               TEXT,
    email               TEXT,
    relationship        TEXT,
    nickname            TEXT,
    notes               TEXT,
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

## Testing

### Test Memory Manager

```bash
python -c "
from aladdin_core.memory_manager import MemoryManager
from aladdin_core.config import MemoryCfg

mem = MemoryManager(MemoryCfg(db_path='data/test_memory.sqlite'))

# Test profile
mem.set_user_name('Test User')
assert mem.get_user_name() == 'Test User'

# Test preferences
mem.set_preference('voice', 'test-voice')
assert mem.get_preference('voice') == 'test-voice'

# Test facts
mem.remember_fact('test_key', 'test_value')
assert mem.recall_fact('test_key') == 'test_value'

# Test contacts
cid = mem.add_contact('Test Contact', phone='555-0000')
assert mem.get_contact(cid)['name'] == 'Test Contact'

print('All tests passed!')
"
```

## Backward Compatibility

✅ **Fully backward compatible** with existing `memory.py`

- Old `ConversationMemory` class continues to work
- New `MemoryManager` extends functionality
- Configuration remains the same
- No breaking changes to main.py

## Future Extensions

### Phase 3 Part 2 (Delivered):
- Projects, locations, reminders, calendar and conversation summaries

### Phase 3 Part 3 (Delivered in this repository):
- Memory importance scoring
- Memory ranking
- Semantic search across memory layers
- Embedding manager
- Vector database

### Additional Database Backends:
- JSON file storage
- PostgreSQL
- MongoDB
- Cloud storage (AWS S3, etc.)

## Files Modified/Added

**New Files:**
- `aladdin_core/memory_manager.py` — Central orchestrator
- `aladdin_core/user_profile.py` — User profile manager
- `aladdin_core/preferences.py` — Preferences manager
- `aladdin_core/facts.py` — Facts manager
- `aladdin_core/contacts.py` — Contacts manager

**Updated Files:**
- `main.py` — No changes needed (still works with existing memory)
- `config.yaml` — No changes needed
- `README.md` — Documentation updated

## Version

- **Aladdin Version**: 2.0.0
- **Phase**: 3 (Smart Memory Part 1)
- **Status**: Complete and tested

## License

MIT — See LICENSE file

---

> 📘 **Phase 3 Part 2 is now available** — adds Projects, Locations,
> Reminders, Calendar and Conversation Summaries. See
> [`SMART_MEMORY_PHASE3_PART2_README.md`](SMART_MEMORY_PHASE3_PART2_README.md).



> 📘 **Phase 3 Part 3 is now available** — adds semantic retrieval,
> importance scoring, ranking and a vector database. See
> [`SMART_MEMORY_PHASE3_PART3_README.md`](SMART_MEMORY_PHASE3_PART3_README.md).
