import os
import shutil
import logging
from datetime import datetime

logger = logging.getLogger(__name__)

def backup_databases(src_dir: str, backup_dir: str):
    """
    Copies all .sqlite files from src_dir to backup_dir with a timestamp.
    """
    if not os.path.exists(src_dir):
        logger.warning(f"Source directory {src_dir} does not exist. Skipping backup.")
        return

    os.makedirs(backup_dir, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    
    backup_count = 0
    for root, _, files in os.walk(src_dir):
        for file in files:
            if file.endswith(".sqlite"):
                src_path = os.path.join(root, file)
                # Create a timestamped filename
                name, ext = os.path.splitext(file)
                dest_filename = f"{name}_{timestamp}{ext}"
                dest_path = os.path.join(backup_dir, dest_filename)
                
                try:
                    shutil.copy2(src_path, dest_path)
                    logger.info(f"Backed up {src_path} to {dest_path}")
                    backup_count += 1
                except Exception as e:
                    logger.error(f"Failed to backup {src_path}: {e}")
                    
    logger.info(f"Backup complete. {backup_count} databases backed up.")

def restore_database(backup_path: str, target_path: str):
    """
    Restores a database from backup_path to target_path.
    """
    if not os.path.exists(backup_path):
        logger.error(f"Backup file {backup_path} does not exist.")
        return False

    try:
        # Ensure target directory exists
        target_dir = os.path.dirname(target_path)
        if target_dir:
            os.makedirs(target_dir, exist_ok=True)
            
        shutil.copy2(backup_path, target_path)
        logger.info(f"Restored database from {backup_path} to {target_path}")
        return True
    except Exception as e:
        logger.error(f"Failed to restore database from {backup_path}: {e}")
        return False
