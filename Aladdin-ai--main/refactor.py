import sys, re

def process_file(filepath):
    try:
        with open(filepath, 'r') as f:
            lines = f.readlines()
    except FileNotFoundError:
        return
    
    out_lines = []
    i = 0
    while i < len(lines):
        line = lines[i]
        out_lines.append(line)
        
        m = re.search(r'([a-zA-Z0-9_\.]+)\s*=\s*sqlite3\.connect\(', line)
        if m:
            var_name = m.group(1)
            # Find indentation
            indent = line[:len(line) - len(line.lstrip())]
            pragma_line = f'{indent}{var_name}.execute("PRAGMA foreign_keys = ON")\n'
            
            # Check if next line already has it
            if i + 1 < len(lines) and 'PRAGMA foreign_keys = ON' in lines[i+1]:
                pass # Already there
            else:
                out_lines.append(pragma_line)
        i += 1
            
    with open(filepath, 'w') as f:
        f.writelines(out_lines)

files = """/home/runner/workspace/work/Aladdin-ai--main/aladdin_core/calendar_memory.py
/home/runner/workspace/work/Aladdin-ai--main/aladdin_core/contacts.py
/home/runner/workspace/work/Aladdin-ai--main/aladdin_core/conversation_summary.py
/home/runner/workspace/work/Aladdin-ai--main/aladdin_core/facts.py
/home/runner/workspace/work/Aladdin-ai--main/aladdin_core/location_memory.py
/home/runner/workspace/work/Aladdin-ai--main/aladdin_core/memory.py
/home/runner/workspace/work/Aladdin-ai--main/aladdin_core/preferences.py
/home/runner/workspace/work/Aladdin-ai--main/aladdin_core/project_memory.py
/home/runner/workspace/work/Aladdin-ai--main/aladdin_core/reminder_memory.py
/home/runner/workspace/work/Aladdin-ai--main/aladdin_core/user_profile.py
/home/runner/workspace/work/Aladdin-ai--main/aladdin_core/vector_store.py
/home/runner/workspace/work/Aladdin-ai--main/backend/calendar/endpoints.py
/home/runner/workspace/work/Aladdin-ai--main/backend/db/schema_verification.py
/home/runner/workspace/work/Aladdin-ai--main/backend/intelligence/context_manager.py
/home/runner/workspace/work/Aladdin-ai--main/backend/intelligence/habit_predictor.py
/home/runner/workspace/work/Aladdin-ai--main/backend/intelligence/location_service.py
/home/runner/workspace/work/Aladdin-ai--main/backend/intelligence/mood_analyzer.py
/home/runner/workspace/work/Aladdin-ai--main/backend/intelligence/personalization_manager.py
/home/runner/workspace/work/Aladdin-ai--main/backend/intelligence/recommendation_engine.py
/home/runner/workspace/work/Aladdin-ai--main/backend/intelligence/reminder_service.py
/home/runner/workspace/work/Aladdin-ai--main/backend/llm/session_manager.py
/home/runner/workspace/work/Aladdin-ai--main/backend/memory/contacts.py
/home/runner/workspace/work/Aladdin-ai--main/backend/memory/locations.py
/home/runner/workspace/work/Aladdin-ai--main/backend/memory/long_term.py
/home/runner/workspace/work/Aladdin-ai--main/backend/memory/migration.py
/home/runner/workspace/work/Aladdin-ai--main/backend/memory/preferences.py
/home/runner/workspace/work/Aladdin-ai--main/backend/memory/profile.py
/home/runner/workspace/work/Aladdin-ai--main/backend/memory/projects.py
/home/runner/workspace/work/Aladdin-ai--main/backend/memory/semantic.py
/home/runner/workspace/work/Aladdin-ai--main/backend/memory/short_term.py
/home/runner/workspace/work/Aladdin-ai--main/backend/reminders/manager.py
/home/runner/workspace/work/Aladdin-ai--main/backend/security/jwt_auth.py
/home/runner/workspace/work/Aladdin-ai--main/backend/security/rate_limiter.py
/home/runner/workspace/work/Aladdin-ai--main/backend/security/security_manager.py
/home/runner/workspace/work/Aladdin-ai--main/backend/security/user_db.py
/home/runner/workspace/work/Aladdin-ai--main/backend/tools/calendar.py
/home/runner/workspace/work/Aladdin-ai--main/backend/tools/notes.py
/home/runner/workspace/work/Aladdin-ai--main/backend/tools/reminder.py
/home/runner/workspace/work/Aladdin-ai--main/memory.py
/home/runner/workspace/work/Aladdin-ai--main/multilingual_memory.py
/home/runner/workspace/work/Aladdin-ai--main/reliability_ext/backup_system.py
/home/runner/workspace/work/Aladdin-ai--main/reliability_ext/restore_system.py
/home/runner/workspace/work/Aladdin-ai--main/tests/test_reliability.py""".split('\n')

for f in files:
    process_file(f)
