"""Vision routes — Phase 6 + Phase 7 image analysis, OCR, face, scene, gesture, memory."""
from __future__ import annotations
import json, logging
from typing import List, Optional
from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import StreamingResponse

log = logging.getLogger(__name__)
router = APIRouter(prefix="/vision", tags=["Vision"])

_IMAGE_TYPES = {"image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif"}


def _mime(upload: UploadFile) -> str:
    ct = upload.content_type or "image/jpeg"
    return ct.split(";")[0].strip()


def _get_vm():
    from ..main import app_state
    vm = app_state.get("vision")
    if not vm:
        raise HTTPException(503, "Vision system not initialized")
    return vm


# ── Phase 6 routes ────────────────────────────────────────────────────────────

@router.post("/upload")
async def upload_image(file: UploadFile = File(...)):
    """Validate and upload an image — returns metadata."""
    mime = _mime(file)
    if mime not in _IMAGE_TYPES:
        raise HTTPException(400, f"Unsupported image type: {mime}. Use JPEG, PNG, or WebP.")
    data = await file.read()
    if len(data) > 20 * 1024 * 1024:
        raise HTTPException(413, "Image too large (max 20 MB)")
    return {"filename": file.filename, "size_bytes": len(data), "mime_type": mime,
            "message": "Upload received. Call /vision with the image."}


@router.post("")
async def analyze_image(
    file: UploadFile = File(...),
    prompt: str = Form(default="Describe this image in detail."),
    provider: str = Form(default=None),
):
    vm = _get_vm()
    mime = _mime(file)
    image_bytes = await file.read()
    try:
        return await vm.analyze(image_bytes, prompt=prompt, provider=provider or None, mime_type=mime)
    except Exception as exc:
        log.error("Vision analyze error: %s", exc)
        raise HTTPException(502, str(exc))


@router.post("/ocr")
async def ocr_image(
    file: UploadFile = File(...),
    language: str = Form(default="eng"),
):
    """Phase 7.5 — OCR with language support (eng, hin, ara, chi_sim, jpn, etc.)"""
    vm = _get_vm()
    image_bytes = await file.read()
    try:
        return await vm.ocr(image_bytes, language=language or None)
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.post("/ocr/tables")
async def ocr_tables(file: UploadFile = File(...)):
    """Phase 7.5 — Extract tables and text from image."""
    vm = _get_vm()
    image_bytes = await file.read()
    try:
        return await vm.ocr_advanced(image_bytes, extract_tables=True)
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.post("/ocr/forms")
async def ocr_forms(file: UploadFile = File(...)):
    """Phase 7.5 — Extract form Label: Value pairs from image."""
    vm = _get_vm()
    image_bytes = await file.read()
    try:
        return await vm.ocr_advanced(image_bytes, extract_forms=True)
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.post("/detect")
async def detect_objects(file: UploadFile = File(...)):
    vm = _get_vm()
    image_bytes = await file.read()
    try:
        return await vm.detect_objects(image_bytes)
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.post("/screenshot")
async def analyze_screenshot(
    file: UploadFile = File(...),
    context: str = Form(default=None),
):
    vm = _get_vm()
    mime = _mime(file)
    image_bytes = await file.read()
    try:
        return await vm.analyze_screenshot(image_bytes, context=context or None, mime_type=mime)
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.post("/screenshot/explain")
async def explain_screenshot(
    file: UploadFile = File(...),
    goal: str = Form(default=""),
):
    """Phase 7.7 — Explain errors and guide user from screenshot."""
    vm = _get_vm()
    image_bytes = await file.read()
    try:
        return await vm.explain_screenshot(image_bytes, goal=goal)
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.post("/pdf")
async def analyze_pdf(file: UploadFile = File(...)):
    vm = _get_vm()
    if file.content_type and "pdf" not in file.content_type:
        raise HTTPException(400, "File must be a PDF")
    pdf_bytes = await file.read()
    try:
        return await vm.analyze_pdf(pdf_bytes)
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.post("/stream")
async def stream_vision(
    file: UploadFile = File(...),
    prompt: str = Form(default="Describe this image."),
    provider: str = Form(default=None),
):
    """Streaming vision analysis via SSE."""
    vm = _get_vm()
    mime = _mime(file)
    image_bytes = await file.read()
    client = vm._pick_vision_client(provider or None)
    if client is None:
        raise HTTPException(503, "No vision provider configured")

    async def event_gen():
        try:
            async for token in client.stream_analyze(image_bytes, prompt=prompt, mime_type=mime):
                yield f"data: {json.dumps({'text': token, 'done': False})}\n\n"
        except Exception as exc:
            yield f"data: {json.dumps({'error': str(exc)})}\n\n"
        yield f"data: {json.dumps({'text': '', 'done': True})}\n\n"

    return StreamingResponse(event_gen(), media_type="text/event-stream")


# ── Phase 7 routes ────────────────────────────────────────────────────────────

@router.post("/faces/detect")
async def detect_faces(file: UploadFile = File(...)):
    """Phase 7.2 — Detect and identify faces in an image."""
    vm = _get_vm()
    image_bytes = await file.read()
    try:
        return await vm.detect_faces(image_bytes)
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.post("/faces/register")
async def register_person(
    file: UploadFile = File(...),
    name: str = Form(...),
    notes: str = Form(default=""),
):
    """Phase 7.2 — Register a person in the face database."""
    vm = _get_vm()
    image_bytes = await file.read()
    try:
        return await vm.register_person(name, image_bytes, notes)
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.get("/faces/people")
async def list_people():
    """Phase 7.2 — List all registered people."""
    vm = _get_vm()
    try:
        return await vm.list_registered_people()
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.post("/scene")
async def understand_scene(file: UploadFile = File(...)):
    """Phase 7.4 — Scene understanding: environment, activities, objects, summary."""
    vm = _get_vm()
    image_bytes = await file.read()
    try:
        return await vm.understand_scene(image_bytes)
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.get("/live/analysis")
async def get_live_analysis():
    """Phase 7.6 — Get latest live camera frame analysis."""
    vm = _get_vm()
    try:
        return await vm.get_live_analysis()
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.post("/gesture")
async def detect_gesture(file: UploadFile = File(...)):
    """Phase 7.8 — Detect hand gestures in a camera frame."""
    vm = _get_vm()
    frame_bytes = await file.read()
    try:
        return await vm.process_gesture(frame_bytes)
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.post("/memory/update")
async def update_object_memory(
    detections: str = Form(...),   # JSON list of {label, confidence, bbox}
    scene_context: str = Form(default=""),
):
    """Phase 7.9 — Update object memory with new detections."""
    vm = _get_vm()
    try:
        det_list = json.loads(detections)
    except Exception:
        raise HTTPException(400, "detections must be a valid JSON list")
    try:
        return await vm.update_object_memory(det_list, scene_context)
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.get("/memory/query")
async def query_object_memory(label: Optional[str] = None):
    """Phase 7.9 — Query object memory by label or get full summary."""
    vm = _get_vm()
    try:
        return await vm.query_object_memory(label)
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.post("/voice-vision")
async def vision_voice_command(
    command: str = Form(...),
    file: Optional[UploadFile] = File(default=None),
):
    """Phase 7.10 — Multimodal voice + vision command handler."""
    vm = _get_vm()
    image_bytes = None
    if file is not None:
        image_bytes = await file.read()
    try:
        return await vm.vision_voice_handle(command, image_bytes)
    except Exception as exc:
        raise HTTPException(502, str(exc))


@router.get("/capabilities")
async def get_capabilities():
    """Returns which vision capabilities are currently active."""
    vm = _get_vm()
    return {"capabilities": vm.capabilities}
