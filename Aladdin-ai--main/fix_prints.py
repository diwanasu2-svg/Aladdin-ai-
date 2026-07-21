import os
import re

def process_file(filepath):
    if "test" in filepath or "tests" in filepath.split(os.sep):
        return

    with open(filepath, 'r') as f:
        lines = f.readlines()
        
    has_logging = any('import logging' in line or 'import log' in line or 'logger' in line for line in lines)
    if not has_logging:
        has_logging = any('logging' in line for line in lines)

    new_lines = []
    print_regex = re.compile(r'^(\s*)print\((.*)\)(\s*)$', re.DOTALL)
    
    modified = False
    for line in lines:
        if 'print(' in line and not line.strip().startswith('#') and '"""' not in line and "'''" not in line:
            m = re.match(r'^(\s*)print\((.*)\)(\s*)$', line)
            if m:
                indent, content, trailing = m.groups()
                
                content_str = content.lower()
                if content_str.startswith('"error') or content_str.startswith("'error") or content_str.startswith('f"error') or content_str.startswith("f'error") or content_str.startswith('"warning') or content_str.startswith("'warning") or content_str.startswith('f"warning') or content_str.startswith("f'warning"):
                    level = 'warning'
                elif content_str.startswith('"debug') or content_str.startswith("'debug") or content_str.startswith('f"debug') or content_str.startswith("f'debug"):
                    level = 'debug'
                else:
                    level = 'info'
                    
                # Fix f-strings by just leaving them as f-strings for logger since logger supports string formatting but we can just use f-strings directly if they exist, except the instructions suggest: print(f"...{var}") → log.info("...%s", var) or keep f-string.
                # So we can keep f-string: log.info(f"...{var}")
                
                new_line = f"{indent}log.{level}({content}){trailing}\n"
                new_lines.append(new_line)
                modified = True
            else:
                # might be inline print like `if True: print("x")`
                m2 = re.search(r'print\((.*?)\)', line)
                if m2:
                    content = m2.group(1)
                    content_str = content.lower()
                    if content_str.startswith('"error') or content_str.startswith("'error") or content_str.startswith('f"error') or content_str.startswith("f'error") or content_str.startswith('"warning') or content_str.startswith("'warning") or content_str.startswith('f"warning') or content_str.startswith("f'warning"):
                        level = 'warning'
                    elif content_str.startswith('"debug') or content_str.startswith("'debug") or content_str.startswith('f"debug') or content_str.startswith("f'debug"):
                        level = 'debug'
                    else:
                        level = 'info'
                    new_line = line.replace(f"log.info({content})", f"log.{level}({content})")
                    new_lines.append(new_line)
                    modified = True
                else:
                    new_lines.append(line)
        else:
            new_lines.append(line)
            
    if modified:
        # Add import logging and log = logging.getLogger(__name__) if not present
        has_import = False
        has_log_decl = False
        for i, line in enumerate(new_lines):
            if 'import logging' in line:
                has_import = True
            if 'log = logging.getLogger' in line or 'logger = logging.getLogger' in line:
                has_log_decl = True
                
        if not (has_import and has_log_decl):
            # insert after first imports
            for i, line in enumerate(new_lines):
                if line.startswith('import ') or line.startswith('from '):
                    if not has_import:
                        new_lines.insert(i, "import logging\n")
                    if not has_log_decl:
                        new_lines.insert(i+1, "log = logging.getLogger(__name__)\n")
                    break
            else:
                if not has_import:
                    new_lines.insert(0, "import logging\n")
                if not has_log_decl:
                    new_lines.insert(1, "log = logging.getLogger(__name__)\n")

        with open(filepath, 'w') as f:
            f.writelines(new_lines)
        log.info(f"Modified {filepath}")


for root, _, files in os.walk('/home/runner/workspace/work/Aladdin-ai--main/'):
    for f in files:
        if f.endswith('.py'):
            process_file(os.path.join(root, f))
