"""File handling routes — upload, parse, summarize, Q&A."""
from __future__ import annotations
import logging
from typing import Optional
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from pydantic import BaseModel

log = logging.getLogger(__name__)
router = APIRouter(prefix="/files", tags=["Files"])

MAX_FILE_SIZE = 50 * 1024 * 1024  # 50 MB

SUPPORTED_TYPES = {
    "application/pdf": "pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "docx",
    "application/msword": "docx",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": "xlsx",
    "application/vnd.ms-excel": "xls",
    "text/plain": "txt",
    "text/csv": "csv",
    "application/json": "json",
    "text/markdown": "md",
    "image/jpeg": "jpg",
    "image/png": "png",
    "image/webp": "webp",
    "image/gif": "gif",
}


def _ext_from_upload(upload: UploadFile) -> str:
    ct = (upload.content_type or "").split(";")[0].strip()
    if ct in SUPPORTED_TYPES:
        return SUPPORTED_TYPES[ct]
    name = upload.filename or ""
    if "." in name:
        return name.rsplit(".", 1)[-1].lower()
    return "bin"


async def _parse_file(file_id: str, data: bytes, ext: str, filename: str) -> dict:
    from ..files.parsers import get_parser
    Parser = get_parser(ext)
    if Parser is None:
        return {"error": f"No parser for .{ext}", "text": ""}
    parser = Parser()
    if ext == "txt" or ext in ("csv", "json", "md"):
        return await parser.parse(data, filename)
    return await parser.parse(data)


@router.post("/upload", status_code=201)
async def upload_file(file: UploadFile = File(...)):
    from ..main import app_state
    store = app_state.get("file_store")
    if not store:
        raise HTTPException(503, "File store not initialized")
    data = await file.read()
    if len(data) > MAX_FILE_SIZE:
        raise HTTPException(413, f"File too large (max {MAX_FILE_SIZE//1024//1024} MB)")
    ext = _ext_from_upload(file)
    record = store.save(data, file.filename or "upload", file.content_type or "")
    return {"message": "Uploaded", "file_id": record["id"], **record}


@router.get("/list")
async def list_files():
    from ..main import app_state
    store = app_state.get("file_store")
    if not store:
        raise HTTPException(503, "File store not initialized")
    return {"files": store.list_files()}


@router.delete("/delete")
async def delete_file(file_id: str):
    from ..main import app_state
    store = app_state.get("file_store")
    if not store:
        raise HTTPException(503, "File store not initialized")
    ok = store.delete(file_id)
    if not ok:
        raise HTTPException(404, "File not found")
    return {"deleted": file_id}


@router.post("/parse")
async def parse_file(
    file_id: Optional[str] = Form(default=None),
    file: Optional[UploadFile] = File(default=None),
):
    from ..main import app_state
    store = app_state.get("file_store")

    if file_id and store:
        record = store.get_record(file_id)
        if not record:
            raise HTTPException(404, "File not found")
        data = store.read_bytes(file_id)
        ext = record.get("extension", "txt")
        filename = record.get("original_name", "")
    elif file:
        data = await file.read()
        ext = _ext_from_upload(file)
        filename = file.filename or "upload"
    else:
        raise HTTPException(400, "Provide file_id or upload a file")

    result = await _parse_file(file_id or "upload", data, ext, filename)
    return {"filename": filename, "extension": ext, **result}


@router.post("/summarize")
async def summarize_file(
    file_id: Optional[str] = Form(default=None),
    file: Optional[UploadFile] = File(default=None),
    prompt: Optional[str] = Form(default=None),
):
    from ..main import app_state
    store = app_state.get("file_store")
    summarizer = app_state.get("file_summarizer")
    if not summarizer:
        raise HTTPException(503, "File summarizer not initialized")

    if file_id and store:
        record = store.get_record(file_id)
        if not record:
            raise HTTPException(404, "File not found")
        data = store.read_bytes(file_id)
        ext = record.get("extension", "txt")
        filename = record.get("original_name", "")
    elif file:
        data = await file.read()
        ext = _ext_from_upload(file)
        filename = file.filename or "upload"
    else:
        raise HTTPException(400, "Provide file_id or upload a file")

    parsed = await _parse_file(file_id or "upload", data, ext, filename)
    if "error" in parsed:
        raise HTTPException(400, parsed["error"])
    return await summarizer.summarize(parsed, filename=filename, custom_prompt=prompt)


@router.post("/qa")
async def file_qa(
    question: str = Form(...),
    file_id: Optional[str] = Form(default=None),
    file: Optional[UploadFile] = File(default=None),
):
    from ..main import app_state
    store = app_state.get("file_store")
    qa = app_state.get("file_qa")
    if not qa:
        raise HTTPException(503, "File Q&A not initialized")

    if file_id and store:
        record = store.get_record(file_id)
        if not record:
            raise HTTPException(404, "File not found")
        data = store.read_bytes(file_id)
        ext = record.get("extension", "txt")
        filename = record.get("original_name", "")
    elif file:
        data = await file.read()
        ext = _ext_from_upload(file)
        filename = file.filename or "upload"
    else:
        raise HTTPException(400, "Provide file_id or upload a file")

    parsed = await _parse_file(file_id or "upload", data, ext, filename)
    if "error" in parsed:
        raise HTTPException(400, parsed["error"])
    return await qa.ask(question, parsed, filename=filename)
