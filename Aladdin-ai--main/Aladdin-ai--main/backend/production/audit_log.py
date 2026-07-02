import logging
import asyncio
from datetime import datetime
from logging.handlers import RotatingFileHandler

class AuditLogger:
    def __init__(self, log_file="audit.log"):
        self.logger = logging.getLogger("AuditLogger")
        self.logger.setLevel(logging.INFO)
        
        # Avoid adding multiple handlers if instantiated multiple times
        if not self.logger.handlers:
            handler = RotatingFileHandler(log_file, maxBytes=5*1024*1024, backupCount=5)
            formatter = logging.Formatter('%(asctime)s - %(message)s')
            handler.setFormatter(formatter)
            self.logger.addHandler(handler)
            
        self._lock = asyncio.Lock()

    async def log_action(self, user_id: str, action: str, resource: str, ip_address: str, result: str):
        """
        Logs an audit event asynchronously.
        """
        log_entry = f"User: {user_id} | Action: {action} | Resource: {resource} | IP: {ip_address} | Result: {result}"
        async with self._lock:
            # Running in executor to prevent blocking the event loop on file I/O
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(None, self.logger.info, log_entry)

# Global instance for easy import
audit_logger = AuditLogger()
