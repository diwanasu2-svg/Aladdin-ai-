def safe_update(target: dict, updates: dict, allowed_keys: set = None) -> dict:
    """Safely update a dict, optionally restricting to allowed keys."""
    if allowed_keys is not None:
        updates = {k: v for k, v in updates.items() if k in allowed_keys}
    target.update(updates)
    return target
