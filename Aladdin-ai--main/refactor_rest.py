import re
import sys

def modify_chat_py():
    with open('/home/runner/workspace/work/Aladdin-ai--main/backend/routes/chat.py', 'r') as f:
        content = f.read()

    sanitize_func = '''
def _sanitize_user_input(text: str) -> str:
    """Block common prompt injection patterns."""
    import re
    INJECTION_PATTERNS = [
        r"ignore previous instructions",
        r"ignore all instructions",
        r"disregard.{0,20}instructions",
        r"you are now",
        r"new system prompt",
        r"act as.{0,30}(AI|assistant|GPT|Claude|model)",
        r"pretend.{0,30}(you are|to be)",
        r"jailbreak",
        r"DAN mode",
    ]
    lower = text.lower()
    for pattern in INJECTION_PATTERNS:
        if re.search(pattern, lower, re.IGNORECASE):
            log.warning("Potential prompt injection detected: %s", pattern)
            raise HTTPException(400, "Input contains disallowed content")
    if len(text) > 10000:
        raise HTTPException(400, "Input too long (max 10000 chars)")
    return text.strip()
'''

    # Insert sanitize function before router = APIRouter(...)
    if '_sanitize_user_input' not in content:
        content = content.replace('router = APIRouter(prefix="/chat", tags=["Chat"])', sanitize_func + '\nrouter = APIRouter(prefix="/chat", tags=["Chat"])')

    # Apply to chat endpoint
    content = content.replace('history.append({"role": "user", "content": request.message})', 
                              'request.message = _sanitize_user_input(request.message)\n    history.append({"role": "user", "content": request.message})')
                              
    with open('/home/runner/workspace/work/Aladdin-ai--main/backend/routes/chat.py', 'w') as f:
        f.write(content)


def modify_app_automation_py():
    with open('/home/runner/workspace/work/Aladdin-ai--main/backend/computer_control/app_automation.py', 'r') as f:
        content = f.read()
        
    allowlist = '''
DESKTOP_APP_ALLOWLIST = {
    # Windows
    "chrome", "google chrome", "firefox", "code", "visual studio code", 
    "notepad", "notepad++", "explorer", "word", "excel", "outlook",
    "terminal", "cmd", "powershell", "calculator",
    # macOS
    "safari", "terminal", "finder", "textedit",
    # Linux
    "gedit", "nautilus", "xterm", "gnome-terminal",
}
'''
    if 'DESKTOP_APP_ALLOWLIST' not in content:
        content = content.replace('class LaunchAppTool(BaseTool):', allowlist + '\nclass LaunchAppTool(BaseTool):')

    # desktop execution branch:
    replacement = '''
            else:
                # Desktop
                app_lower = app.lower()
                if app_lower not in DESKTOP_APP_ALLOWLIST:
                    return ToolResult(False, self.name, 
                                     error=f"App '{app}' is not in the allowed list. Allowed: {sorted(DESKTOP_APP_ALLOWLIST)}",
                                     duration_ms=(time.time() - t0) * 1000)
                if _SYSTEM == "Windows":'''
                
    content = content.replace('''
            else:
                # Desktop
                if _SYSTEM == "Windows":''', replacement)

    with open('/home/runner/workspace/work/Aladdin-ai--main/backend/computer_control/app_automation.py', 'w') as f:
        f.write(content)

modify_chat_py()
modify_app_automation_py()
