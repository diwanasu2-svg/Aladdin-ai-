"""Contacts tools — wraps memory.contacts with tool interface."""
from __future__ import annotations
import logging, time
from typing import Optional
from .base import BaseTool, ToolResult

log = logging.getLogger(__name__)
_contacts_mem = None


def init(contacts_memory):
    global _contacts_mem
    _contacts_mem = contacts_memory


class AddContactTool(BaseTool):
    name = "add_contact"
    description = "Add a new contact."
    parameters = {"type": "object", "properties": {
        "name": {"type": "string"}, "phone": {"type": "string"},
        "email": {"type": "string"}, "relation": {"type": "string"},
        "notes": {"type": "string"}}, "required": ["name"]}

    async def execute(self, name: str, phone: str = None, email: str = None,
                      relation: str = None, notes: str = None) -> ToolResult:
        if not _contacts_mem: return ToolResult(False, self.name, error="Contacts not initialized")
        cid = _contacts_mem.add(name, phone, email, relation, notes)
        return ToolResult(True, self.name, {"id": cid, "name": name})


class ListContactsTool(BaseTool):
    name = "list_contacts"
    description = "List all contacts."
    parameters = {"type": "object", "properties": {}}

    async def execute(self) -> ToolResult:
        if not _contacts_mem: return ToolResult(False, self.name, error="Contacts not initialized")
        contacts = _contacts_mem.list_all()
        return ToolResult(True, self.name, {"contacts": contacts, "count": len(contacts)})


class SearchContactsTool(BaseTool):
    name = "search_contacts"
    description = "Search contacts by name, phone, or email."
    parameters = {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}

    async def execute(self, query: str) -> ToolResult:
        if not _contacts_mem: return ToolResult(False, self.name, error="Contacts not initialized")
        all_c = _contacts_mem.list_all()
        q = query.lower()
        matches = [c for c in all_c if q in (c.get("name") or "").lower()
                   or q in (c.get("phone") or "").lower()
                   or q in (c.get("email") or "").lower()]
        return ToolResult(True, self.name, {"contacts": matches, "count": len(matches)})


class DeleteContactTool(BaseTool):
    name = "delete_contact"
    description = "Delete a contact by ID."
    parameters = {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}

    async def execute(self, id: str) -> ToolResult:
        if not _contacts_mem: return ToolResult(False, self.name, error="Contacts not initialized")
        ok = _contacts_mem.delete(id)
        return ToolResult(ok, self.name, {"deleted": id} if ok else None,
                          error=None if ok else "Not found")
