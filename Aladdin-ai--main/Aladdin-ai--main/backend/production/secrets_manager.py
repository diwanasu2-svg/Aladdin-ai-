import os
import logging

logger = logging.getLogger(__name__)

class SecretsManager:
    def __init__(self, env_file=".env"):
        self.secrets = {}
        self._load_from_env()
        self._load_from_file(env_file)

    def _load_from_env(self):
        """Loads all secrets currently in the environment variables."""
        for key, value in os.environ.items():
            self.secrets[key] = value

    def _load_from_file(self, env_file):
        """Loads secrets from a .env file if it exists."""
        if not os.path.exists(env_file):
            return
            
        try:
            with open(env_file, 'r') as f:
                for line in f:
                    line = line.strip()
                    # Ignore comments and empty lines
                    if not line or line.startswith('#'):
                        continue
                        
                    # Parse key=value
                    if '=' in line:
                        key, value = line.split('=', 1)
                        key = key.strip()
                        value = value.strip()
                        # Remove quotes if present
                        if (value.startswith('"') and value.endswith('"')) or \
                           (value.startswith("'") and value.endswith("'")):
                            value = value[1:-1]
                            
                        self.secrets[key] = value
        except Exception as e:
            logger.error(f"Error loading secrets from {env_file}: {e}")

    def get_secret(self, key: str, default=None):
        """
        Retrieves a secret. Never logs the value.
        """
        return self.secrets.get(key, default)

    def validate_required_secrets(self, keys: list):
        """
        Ensures all required secrets are present.
        Raises ValueError if any are missing.
        """
        missing_keys = [key for key in keys if key not in self.secrets or not self.secrets[key]]
        if missing_keys:
            error_msg = f"Missing required secrets: {', '.join(missing_keys)}"
            logger.error(error_msg)
            raise ValueError(error_msg)
            
# Global instance
secrets_manager = SecretsManager()
