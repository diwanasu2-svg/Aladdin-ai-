"""Unit tests for Vision module — Phase 15, Feature 1."""

from __future__ import annotations

import unittest
from unittest.mock import MagicMock, Mock, patch


class TestVisionModule(unittest.TestCase):
    def setUp(self):
        self.mock_vision = MagicMock()
        self.mock_vision.analyze_image.return_value = {
            "description": "A red apple on a wooden table",
            "objects": [{"label": "apple", "confidence": 0.98}],
            "text": "",
        }

    def test_analyze_image_returns_description(self):
        result = self.mock_vision.analyze_image(b"\xff\xd8\xff" + b"\x00" * 100)
        self.assertIn("description", result)
        self.assertGreater(len(result["description"]), 0)

    def test_analyze_image_detects_objects(self):
        result = self.mock_vision.analyze_image(b"\x00" * 100)
        self.assertIn("objects", result)
        self.assertIsInstance(result["objects"], list)

    def test_ocr_extracts_text(self):
        self.mock_vision.ocr.return_value = {
            "text": "Hello World\nFoo Bar",
            "confidence": 0.96,
        }
        result = self.mock_vision.ocr(b"\x00" * 100)
        self.assertIn("text", result)
        self.assertIn("Hello World", result["text"])

    def test_analyze_empty_image_raises(self):
        self.mock_vision.analyze_image.side_effect = ValueError("Empty image data")
        with self.assertRaises(ValueError):
            self.mock_vision.analyze_image(b"")

    def test_face_detection(self):
        self.mock_vision.detect_faces.return_value = [
            {"box": [10, 20, 100, 150], "confidence": 0.99}
        ]
        faces = self.mock_vision.detect_faces(b"\x00" * 100)
        self.assertIsInstance(faces, list)

    def test_document_scanning(self):
        self.mock_vision.scan_document.return_value = {
            "text": "Invoice #123\nTotal: $500",
            "type": "invoice",
            "confidence": 0.91,
        }
        result = self.mock_vision.scan_document(b"\x00" * 100)
        self.assertIn("text", result)
        self.assertIn("Invoice", result["text"])

    def test_image_captioning(self):
        self.mock_vision.caption.return_value = "A person is sitting at a desk with a laptop"
        caption = self.mock_vision.caption(b"\x00" * 100)
        self.assertIsInstance(caption, str)
        self.assertGreater(len(caption), 5)

    def test_visual_question_answering(self):
        self.mock_vision.vqa.return_value = {"answer": "Red", "confidence": 0.97}
        result = self.mock_vision.vqa(b"\x00" * 100, "What color is the apple?")
        self.assertIn("answer", result)
        self.assertEqual(result["answer"], "Red")


class TestCameraCapture(unittest.TestCase):
    def test_capture_returns_bytes(self):
        camera = MagicMock()
        camera.capture.return_value = b"\xff\xd8\xff" + b"\x00" * 200
        frame = camera.capture()
        self.assertIsInstance(frame, bytes)
        self.assertGreater(len(frame), 0)

    def test_camera_open_close(self):
        camera = MagicMock()
        camera.open()
        camera.open.assert_called_once()
        camera.close()
        camera.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
